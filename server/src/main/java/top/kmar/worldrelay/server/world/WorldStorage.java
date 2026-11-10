package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;

/** 统计文件逻辑字节数，不读取文件内容、不跟随符号链接，不占用所有权锁。 */
final class WorldStorage {

    private WorldStorage() {
    }

    static void write(JsonWriter writer, WorldStore worlds, Path transfers, WorldMetadata metadata) throws IOException {
        var references = worlds.snapshotReferences(metadata);
        var retained = new HashSet<String>();
        String current = null;
        for (var ref : references) {
            retained.add(ref.uploadId());
            if (java.util.Objects.equals(ref.version(), metadata.state().committedVersion())) current = ref.uploadId();
        }
        String authoritative = current;
        Path root = worlds.directory().resolve(metadata.worldId());
        long[] sizes = new long[5];
        walk(root, (path, bytes) -> {
            Path relative = root.relativize(path);
            String first = relative.getName(0).toString();
            int category;
            if (first.equals("snapshots") && relative.getNameCount() > 1) {
                String id = relative.getName(1).toString();
                category = id.equals(authoritative) ? 0 : retained.contains(id) ? 1 : 2;
            } else category = first.equals("world.json") || first.equals("snapshot-index") ? 4 : 2;
            sizes[category] = Math.addExact(sizes[category], bytes);
        });
        Path sessions = transfers.resolve(".sessions");
        if (Files.exists(sessions, LinkOption.NOFOLLOW_LINKS)) {
            SnapshotFiles.requireDirectory(sessions);
            try (var users = Files.newDirectoryStream(sessions)) {
                for (Path user : users) {
                    if (!user.getFileName().toString().matches("[0-9a-f]{64}")) continue;
                    SnapshotFiles.requireDirectory(user);
                    Path directory = user.resolve(metadata.worldId());
                    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
                        walk(directory, (path, bytes) -> sizes[3] = Math.addExact(sizes[3], bytes));
                }
            }
        }
        long total = 0;
        for (long size : sizes) {
            total = Math.addExact(total, size);
        }
        writer.beginObject().name("currentBytes").value(sizes[0]).name("backupBytes").value(sizes[1])
            .name("temporaryBytes").value(sizes[2]).name("transferBytes").value(sizes[3])
            .name("metadataBytes").value(sizes[4]).name("totalBytes").value(total).endObject();
    }

    private interface FileSize {

        void accept(Path path, long bytes);

    }

    @SuppressWarnings("NullableProblems") // JDK 文件访问器的非空契约不额外引入注解依赖。
    private static void walk(Path root, FileSize consumer) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("Unexpected non-regular storage file");
                consumer.accept(file, attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
