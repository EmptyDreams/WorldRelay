package top.kmar.mc.worldreplay.client;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

/** 游戏线程暂缓常规世界保存，IO 队列检查并复制已保存的文件。 */
final class SharedWorldCheckpoint {

    private SharedWorldCheckpoint() {
    }

    // 与 1.21.1 SaveOffCommand / SaveAllCommand 使用同一套维度保存标志及强制刷盘调用。
    static Map<ServerLevel, Boolean> save(MinecraftServer server) throws IOException {
        Map<ServerLevel, Boolean> original = new HashMap<>();
        boolean saved = false;
        try {
            for (ServerLevel level : server.getAllLevels()) {
                original.put(level, level.noSave);
                level.noSave = true;
            }
            if (!server.saveEverything(true, true, true)) throw new IOException("World save failed");
            saved = true;
            return original;
        } finally {
            if (!saved) restore(original);
        }
    }

    static void restore(Map<ServerLevel, Boolean> original) {
        original.forEach((level, noSave) -> level.noSave = noSave);
    }

    static void copy(Path source, Path payload) throws IOException {
        var before = fileState(source);
        Files.createDirectory(payload);
        SnapshotFiles.copyWorld(source, payload.resolve("world"));
        if (!before.equals(fileState(source))) throw new IOException("World files changed during checkpoint copy");
    }

    record FileState(boolean directory, long size, FileTime modified, Object key) {
    }

    static Map<Path, FileState> fileState(Path source) throws IOException {
        SnapshotFiles.requireDirectory(source);
        Map<Path, FileState> files = new HashMap<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path directory, @NotNull BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source)) add(directory, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes) throws IOException {
                if (file.equals(source.resolve("session.lock"))) return FileVisitResult.CONTINUE;
                if (!attributes.isRegularFile()) throw new IOException("Nonregular checkpoint file");
                add(file, attributes);
                return FileVisitResult.CONTINUE;
            }

            private void add(Path path, BasicFileAttributes attributes) throws IOException {
                if (files.size() >= SnapshotManifest.MAX_ENTRIES) throw new IOException("Too many checkpoint files");
                files.put(source.relativize(path), new FileState(attributes.isDirectory(), attributes.size(),
                    attributes.lastModifiedTime(), attributes.fileKey()));
            }
        });
        return files;
    }
}