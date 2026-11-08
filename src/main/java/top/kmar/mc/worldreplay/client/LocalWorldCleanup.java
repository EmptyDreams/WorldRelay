package top.kmar.mc.worldreplay.client;

import net.minecraft.util.DirectoryLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 每个世界只保留当前工作数据和一份最新缓存；失败副本在新检查点产生前不主动清理。 */
final class LocalWorldCleanup {

    private LocalWorldCleanup() {
    }

    static void clean(Path root, LocalWorldSession session, @Nullable LocalWorldSessionStore.Cache cache) throws IOException {
        SnapshotFiles.requireDirectory(root);
        Path work = root.resolve("work");
        if (Files.notExists(work, LinkOption.NOFOLLOW_LINKS)) return;
        SnapshotFiles.requireDirectory(work);
        Set<Path> keep = new HashSet<>();
        if (cache != null) keep.add(cache.payload());
        if (session.epoch() > 0 && session.stage() != LocalWorldSession.Stage.COMMITTED) {
            Path current = work.resolve(Long.toString(session.epoch()));
            if (session.folder() != null) keep.add(current.resolve("saves").resolve(session.folder()));
            if (session.stage() == LocalWorldSession.Stage.UPLOADING) keep.add(current.resolve("upload"));
            if (session.stage() == LocalWorldSession.Stage.DOWNLOADING) keep.add(current.resolve("download"));
        }
        if (session.localSource() != null) {
            var source = session.localSource();
            keep.add(work.resolve(Long.toString(source.epoch())).resolve("saves").resolve(source.folder()));
        }
        for (Path path : keep) {
            if (!path.toAbsolutePath().normalize().equals(path) || !path.startsWith(work) || path.equals(work))
                throw new IOException("Cleanup protection path is outside managed work directory");
        }
        // 即使恢复记录已被新会话替代，仍不删除被另一个 Minecraft 进程持有的工作目录。
        for (Path epoch : children(work)) {
            SnapshotFiles.requireDirectory(epoch);
            Path saves = epoch.resolve("saves");
            for (Path world : children(saves)) {
                SnapshotFiles.requireDirectory(world);
                Path lock = world.resolve("session.lock");
                if (Files.isSymbolicLink(lock)) throw new IOException("World lock must not be a link");
                try {
                    if (DirectoryLock.isLocked(world)) keep.add(world);
                } catch (OverlappingFileLockException failure) {
                    keep.add(world);
                }
            }
        }
        for (Path epoch : children(work)) {
            prune(epoch, work, keep);
        }
    }

    private static List<Path> children(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        SnapshotFiles.requireDirectory(directory);
        try (var children = Files.list(directory)) {
            return children.toList();
        }
    }

    private static void prune(Path target, Path work, Set<Path> keep) throws IOException {
        checkInterrupted();
        if (!target.startsWith(work) || target.equals(work)) throw new IOException("Cleanup target is outside managed work directory");
        if (keep.contains(target)) return;
        if (keep.stream().anyMatch(path -> path.startsWith(target))) {
            for (Path child : children(target)) {
                prune(child, work, keep);
            }
            return;
        }
        deleteTree(target);
    }

    private static void deleteTree(Path target) throws IOException {
        SnapshotFiles.requireDirectory(target.getParent());
        try (var parent = Files.newDirectoryStream(target.getParent())) {
            if (parent instanceof SecureDirectoryStream<Path> secure) {
                secureDelete(secure, target.getFileName());
                return;
            }
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path directory, @NotNull BasicFileAttributes attributes) throws IOException {
                checkInterrupted();
                SnapshotFiles.requireDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes) throws IOException {
                checkInterrupted();
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path directory, @Nullable IOException failure) throws IOException {
                if (failure != null) throw failure;
                checkInterrupted();
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void secureDelete(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        checkInterrupted();
        var attributes = parent.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (attributes.isDirectory()) {
            try (var directory = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path entry : directory) {
                    secureDelete(directory, entry.getFileName());
                }
            }
            parent.deleteDirectory(name);
        } else parent.deleteFile(name);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Local world cleanup interrupted");
    }

}