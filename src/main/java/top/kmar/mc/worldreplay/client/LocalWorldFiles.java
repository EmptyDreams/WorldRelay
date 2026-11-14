package top.kmar.mc.worldreplay.client;

import net.minecraft.util.DirectoryLock;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.UnixStat;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 仅管理当前服务器和账号的本地文件；导入内容只作增量下载基底，不授予托管资格。 */
final class LocalWorldFiles {

    private LocalWorldFiles() {
    }

    record State(boolean present, boolean unuploaded, boolean ownershipPending) {
    }

    static State inspect(Path root) {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return new State(false, false, false);
        try {
            var session = session(root);
            if (session == null) return new State(true, hasProgressFiles(root, true), false);
            boolean pending = session.localSource() != null || switch (session.stage()) {
                case START_REQUESTED, STARTED, STOPPING, STOPPED, UPLOADING -> true;
                default -> false;
            };
            if (session.stage() != LocalWorldSession.Stage.COMMITTED) pending |= hasProgressFiles(root, false);
            return new State(true, pending, session.sessionId() != null
                && session.stage() != LocalWorldSession.Stage.COMMITTED && session.stage() != LocalWorldSession.Stage.CANCELLED);
        } catch (IOException | RuntimeException failure) {
            // 记录损坏时不能断言进度已经上传；删除确认仍须显示额外提醒。
            return new State(true, true, true);
        }
    }

    private static LocalWorldSession session(Path root) throws IOException {
        return LocalWorldSessionStore.readSession(root, root.getFileName().toString());
    }

    private static boolean hasProgressFiles(Path root, boolean includeUploads) throws IOException {
        Path work = root.resolve("work");
        if (Files.notExists(work, LinkOption.NOFOLLOW_LINKS)) return false;
        SnapshotFiles.requireDirectory(work);
        try (var epochs = Files.newDirectoryStream(work)) {
            for (Path epoch : epochs) {
                if (Files.isDirectory(epoch.resolve("saves"), LinkOption.NOFOLLOW_LINKS)) return true;
                if (includeUploads && Files.isDirectory(epoch.resolve("upload"), LinkOption.NOFOLLOW_LINKS)) return true;
            }
        }
        return false;
    }

    static void delete(Path root, boolean allowUnuploaded) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (inspect(root).unuploaded() && !allowUnuploaded) throw new IOException("Unuploaded progress requires confirmation");
        checkUnused(root);
        LocalWorldCleanup.deleteTree(root);
    }

    /** 调用方已持有客户端目录锁，且与创建/同步任务共用串行队列。 */
    static void checkUnused(Path root) throws IOException {
        Path work = root.resolve("work");
        if (Files.notExists(work, LinkOption.NOFOLLOW_LINKS)) return;
        SnapshotFiles.requireDirectory(work);
        try (var epochs = Files.newDirectoryStream(work)) {
            for (Path epoch : epochs) {
                Path saves = epoch.resolve("saves");
                if (!Files.isDirectory(saves, LinkOption.NOFOLLOW_LINKS)) continue;
                SnapshotFiles.requireDirectory(saves);
                try (var worlds = Files.newDirectoryStream(saves)) {
                    for (Path world : worlds) {
                        if (Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) checkUnlocked(world);
                    }
                }
            }
        }
    }

    private static void checkUnlocked(Path world) throws IOException {
        SnapshotFiles.requireDirectory(world);
        if (Files.isSymbolicLink(world.resolve("session.lock"))) throw new IOException("World lock must not be a link");
        try {
            if (DirectoryLock.isLocked(world)) throw new IOException("Local world is still in use");
        } catch (OverlappingFileLockException failure) {
            throw new IOException("Local world is still in use", failure);
        }
    }

    /** 完整写入临时 ZIP 后才发布，不把恢复记录、传输凭据或进程锁放进存档。 */
    @SuppressWarnings("try") // 持有工作存档的目录锁，阻止其他 Minecraft 进程在导出途中打开它。
    static void exportSnapshot(LocalWorldSessionStore store, Path output, boolean replace) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        Path managed = store.root().getParent().getParent();
        if (target.startsWith(managed) || target.getFileName() == null
            || !target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("Choose a ZIP path outside managed data");
        }
        checkExportTarget(target, replace);
        checkUnused(store.root());
        ExportSource source = exportSource(store);
        SnapshotFiles.requireDirectory(source.world());
        try (var ignored = source.working() ? DirectoryLock.create(source.world()) : null) {
            SnapshotManifest manifest = SnapshotFiles.scan(source.world());
            if (source.expected() != null && !manifest.equals(source.expected())) throw new IOException("Local cache is incomplete or corrupt");
            LocalWorldSessionStore.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".worldrelay-export-", ".zip.part");
            try {
                writeZip(source.world(), manifest, temporary);
                try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                checkExportTarget(target, replace);
                if (replace) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, target); // 不允许覆盖时保留 Files.move 的目标存在检查。
                SnapshotFiles.forceDirectory(target.getParent());
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void checkExportTarget(Path target, boolean replace) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Export target is not a regular file");
            if (!replace) throw new FileAlreadyExistsException(target.toString());
        }
    }

    private record ExportSource(Path world, SnapshotManifest expected, boolean working) {
    }

    private static ExportSource exportSource(LocalWorldSessionStore store) throws IOException {
        var session = store.read();
        if (session != null && session.localSource() != null) {
            var previous = session.localSource();
            return new ExportSource(store.root().resolve("work").resolve(Long.toString(previous.epoch()))
                .resolve("saves").resolve(previous.folder()), null, true);
        }
        if (session != null && switch (session.stage()) {
            case START_REQUESTED, STARTED, STOPPING, STOPPED, UPLOADING -> true;
            default -> false;
        }) {
            Path working = store.root().resolve("work").resolve(Long.toString(session.epoch())).resolve("saves").resolve(session.folder());
            if (Files.exists(working, LinkOption.NOFOLLOW_LINKS)) return new ExportSource(working, null, true);
            if (session.stage() == LocalWorldSession.Stage.UPLOADING) {
                return exportPayload(store.transfer(session.epoch(), "upload"), session.manifestHash());
            }
            throw new IOException("Unuploaded working save is unavailable");
        }
        if (session == null && hasProgressFiles(store.root(), true)) throw new IOException("Cannot identify the working save without recovery records");
        var cache = store.cache();
        if (cache == null) throw new IOException("No complete local save is available");
        return exportPayload(cache.payload(), cache.hash());
    }

    private static ExportSource exportPayload(Path payload, String hash) throws IOException {
        return new ExportSource(payload.resolve("world"), SnapshotManifest.read(payload.resolve(SnapshotManifest.FILE_NAME), hash), false);
    }

    private static void writeZip(Path world, SnapshotManifest manifest, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] buffer = new byte[65536];
        try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : manifest.entries()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
                Path file = world.resolve(entry.path());
                zip.putNextEntry(new ZipEntry(entry.path() + (entry.directory() ? "/" : "")));
                if (!entry.directory()) {
                    SnapshotFiles.requireDirectory(file.getParent());
                    digest.reset();
                    long size = 0;
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
                            size += read;
                            if (size > entry.size()) throw new IOException("World changed during export");
                            zip.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                        }
                    }
                    if (size != entry.size() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256())) {
                        throw new IOException("World changed during export");
                    }
                }
                zip.closeEntry();
            }
        }
    }

    static void importSnapshot(LocalWorldSessionStore store, Path input) throws IOException {
        Path source = input.toAbsolutePath().normalize();
        Path managed = store.root().getParent().getParent();
        if (source.startsWith(managed) || managed.startsWith(source)) throw new IOException("Choose a world outside managed data");
        checkUnused(store.root());
        boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (directory) checkUnlocked(source);
        else if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
            || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("Choose a world folder or ZIP archive");
        }
        String id = UUID.randomUUID().toString();
        Path target = store.imported(id);
        LocalWorldSessionStore.createDirectories(target.getParent());
        Path staged = Files.createDirectory(target.getParent().resolve(".import-" + id));
        boolean moved = false;
        try {
            Path world = staged.resolve("world");
            if (directory) SnapshotFiles.copyWorld(source, world);
            else extract(source, world);
            SnapshotManifest manifest = SnapshotFiles.scan(world);
            byte[] bytes = manifest.encode();
            Files.write(staged.resolve(SnapshotManifest.FILE_NAME), bytes, StandardOpenOption.CREATE_NEW);
            try (var channel = java.nio.channels.FileChannel.open(staged.resolve(SnapshotManifest.FILE_NAME), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            SnapshotFiles.forceDirectory(staged);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            store.cacheImported(id, SnapshotManifest.hash(bytes));
            store.cleanupAfterImport();
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid world snapshot", failure);
        } finally {
            // 索引发布失败时保留可能已被引用的完整副本，下次成功检查点统一清理。
            if (!moved) LocalWorldCleanup.deleteTree(staged);
        }
    }

    private static void extract(Path archive, Path world) throws IOException {
        try (var zip = ZipFile.builder().setPath(archive).get()) {
            List<ZipArchiveEntry> entries = new ArrayList<>();
            Set<String> roots = new HashSet<>();
            boolean rootLevel = false;
            var iterator = zip.getEntries();
            while (iterator.hasMoreElements()) {
                ZipArchiveEntry entry = iterator.nextElement();
                String name = entry.getName();
                String path = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                SnapshotManifest.validatePath(path);
                int type = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
                if (entry.isUnixSymlink() || type != 0 && type != UnixStat.FILE_FLAG && type != UnixStat.DIR_FLAG
                    || !zip.canReadEntryData(entry) || entry.getSize() > SnapshotManifest.MAX_BYTES) {
                    throw new IOException("ZIP contains unsupported entries");
                }
                if (entries.size() >= SnapshotManifest.MAX_ENTRIES + 1) throw new IOException("Too many ZIP entries");
                entries.add(entry);
                if (!entry.isDirectory() && name.equals("level.dat")) rootLevel = true;
                if (!entry.isDirectory() && name.endsWith("/level.dat") && name.indexOf('/') == name.length() - "/level.dat".length()) {
                    roots.add(name.substring(0, name.indexOf('/') + 1));
                }
            }
            if (!rootLevel && roots.size() != 1) throw new IOException("ZIP must contain one world with level.dat");
            String prefix = rootLevel ? "" : roots.iterator().next();
            Files.createDirectory(world);
            long total = 0;
            byte[] buffer = new byte[65536];
            for (ZipArchiveEntry entry : entries) {
                if (!entry.getName().startsWith(prefix)) continue;
                String name = entry.getName().substring(prefix.length());
                if (name.isEmpty() || name.equals("session.lock")) continue;
                Path destination = world.resolve(name).normalize();
                if (!destination.startsWith(world)) throw new IOException("ZIP path escapes world");
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                var crc = new CRC32();
                long size = 0;
                try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
                        total += read;
                        size += read;
                        if (total > SnapshotManifest.MAX_BYTES) throw new IOException("ZIP exceeds snapshot quota");
                        output.write(buffer, 0, read);
                        crc.update(buffer, 0, read);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("ZIP entry is corrupt");
                try (var channel = java.nio.channels.FileChannel.open(destination, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            SnapshotFiles.forceDirectory(world);
        }
    }
}
