package top.kmar.worldrelay.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 不跟随链接，不复用硬链接。所有大型读写由调用方放到后台线程。 */
@SuppressWarnings("NullableProblems") // 共享 JDK-only 源码不为 IDE 的外部空值注解引入依赖。
public final class SnapshotFiles {

    private SnapshotFiles() {
    }

    public static SnapshotManifest scan(Path world, String compatibility) throws IOException {
        List<SnapshotManifest.Entry> entries = new ArrayList<>();
        long[] bytes = {0};
        requireDirectory(world);
        Files.walkFileTree(world, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(world)) add(directory, true, 0, "");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String name = relative(world, file);
                // Minecraft 的进程会话锁不可在另一台机器上复用；不忽略未知模组目录或文件。
                if (name.equals("session.lock")) return FileVisitResult.CONTINUE;
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Snapshot contains a nonregular file");
                bytes[0] = Math.addExact(bytes[0], attributes.size());
                if (bytes[0] > SnapshotManifest.MAX_BYTES) throw new IOException("Snapshot exceeds quota");
                add(file, false, attributes.size(), hashFile(file));
                return FileVisitResult.CONTINUE;
            }

            private void add(Path path, boolean directory, long size, String hash) throws IOException {
                if (entries.size() >= SnapshotManifest.MAX_ENTRIES) throw new IOException("Too many snapshot entries");
                try {
                    entries.add(new SnapshotManifest.Entry(relative(world, path), directory, size, hash));
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Snapshot contains an unsafe path");
                }
            }
        });
        entries.sort(Comparator.comparing(SnapshotManifest.Entry::path));
        try {
            return new SnapshotManifest(compatibility, entries);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid world snapshot");
        }
    }

    public static void verify(Path world, SnapshotManifest expected) throws IOException {
        if (!scan(world, expected.compatibility()).equals(expected)) throw new IOException("World snapshot checksum or file set mismatch");
    }

    public static String hashFile(Path file) throws IOException {
        var digest = SnapshotManifest.digest();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[65536];
            int read;
            while ((read = input.read(bytes)) != -1) {
                digest.update(bytes, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 从可能仍被旧 SSH 进程写入的暂存区复制到私有目录，随后只校验、发布私有副本。
     * Linux 使用目录句柄遍历，防止验证后父目录被替换为符号链接。
     */
    public static void copyIsolated(Path source, Path target, boolean requireSecureTraversal) throws IOException {
        copyIsolated(source, target, requireSecureTraversal, false);
    }

    /** 复制停服后的完整世界；根目录的 Minecraft 进程锁不能被读取或传给下一任房主。 */
    @SuppressWarnings("unused") // 由客户端调用；共享源码在 server 模块中也会独立编译。
    public static void copyWorld(Path source, Path target) throws IOException {
        copyIsolated(source, target, false, true);
    }

    private static void copyIsolated(Path source, Path target, boolean requireSecureTraversal, boolean skipSessionLock) throws IOException {
        requireDirectory(source);
        Files.createDirectory(target);
        long[] quota = {0, 0};
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                secureCopy(secure, target, quota, skipSessionLock);
            } else {
                if (requireSecureTraversal) throw new IOException("Secure directory traversal is unavailable");
                ordinaryCopy(source, target, quota, skipSessionLock);
            }
        }
        forceDirectory(target);
    }

    private static void secureCopy(SecureDirectoryStream<Path> source, Path target, long[] quota, boolean skipSessionLock) throws IOException {
        for (Path item : source) {
            Path name = item.getFileName();
            if (skipSessionLock && name.toString().equals("session.lock")) continue;
            safeName(name);
            BasicFileAttributes attributes = source.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            count(attributes, quota);
            Path destination = target.resolve(name.toString());
            if (attributes.isDirectory()) {
                Files.createDirectory(destination);
                try (SecureDirectoryStream<Path> child = source.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    secureCopy(child, destination, quota, false);
                }
                forceDirectory(destination);
            } else {
                if (!attributes.isRegularFile()) throw new IOException("Nonregular transfer file");
                try (SeekableByteChannel input = source.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    copyFile(input, destination, attributes.size());
                }
            }
        }
    }

    private static void ordinaryCopy(Path source, Path target, long[] quota, boolean skipSessionLock) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path item : stream) {
                if (skipSessionLock && item.getFileName().toString().equals("session.lock")) continue;
                safeName(item.getFileName());
                BasicFileAttributes attributes = Files.readAttributes(item, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                count(attributes, quota);
                Path destination = target.resolve(item.getFileName().toString());
                if (attributes.isDirectory()) {
                    Files.createDirectory(destination);
                    ordinaryCopy(item, destination, quota, false);
                    forceDirectory(destination);
                } else {
                    if (!attributes.isRegularFile()) throw new IOException("Nonregular transfer file");
                    try (var input = Files.newByteChannel(item, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        copyFile(input, destination, attributes.size());
                    }
                }
            }
        }
    }

    private static void copyFile(SeekableByteChannel input, Path target, long size) throws IOException {
        try (FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            long copied = 0;
            while (input.read(buffer) != -1) {
                checkInterrupted();
                copied += buffer.position();
                if (copied > size) throw new IOException("Transfer file changed while sealing");
                buffer.flip();
                while (buffer.hasRemaining()) {
                    // 缓冲区位置决定是否继续写入。
                    output.write(buffer);
                }
                buffer.clear();
            }
            if (copied != size) throw new IOException("Transfer file changed while sealing");
            output.force(true);
        }
    }

    private static void count(BasicFileAttributes attributes, long[] quota) throws IOException {
        checkInterrupted();
        if (++quota[0] > SnapshotManifest.MAX_ENTRIES + 4L) throw new IOException("Too many transfer entries");
        quota[1] += attributes.size();
        if (quota[1] > SnapshotManifest.MAX_BYTES + SnapshotManifest.MAX_MANIFEST_BYTES) throw new IOException("Transfer exceeds quota");
    }

    private static void checkInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Snapshot operation interrupted");
    }

    private static void safeName(Path name) throws IOException {
        try {
            SnapshotManifest.validatePath(name.toString());
        } catch (IllegalArgumentException failure) {
            throw new IOException("Unsafe transfer path");
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public static void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toAbsolutePath().normalize().equals(directory.toRealPath())) {
            throw new IOException("Snapshot directory must not contain links");
        }
    }

    public static void forceDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

}
