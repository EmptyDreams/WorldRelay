package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** 回执淘汰后保留备份的版本、时间和摘要；不保存身份或提供提交幂等结果。 */
record SnapshotReference(String uploadId, long version, String committedAt, String manifestHash) {

    SnapshotReference(String uploadId, long version, String committedAt) {
        this(uploadId, version, committedAt, null);
    }

    SnapshotReference {
        WorldStore.validateId(uploadId);
        if (version < 1) throw new IllegalArgumentException("Invalid snapshot version");
        if (manifestHash != null && !manifestHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid snapshot hash");
        if (committedAt != null) {
            //noinspection ResultOfMethodCallIgnored
            Instant.parse(committedAt);
        }
    }

    static SnapshotReference from(WorldMetadata.Commit commit) {
        return new SnapshotReference(commit.uploadId(), commit.version(), commit.committedAt(), commit.manifestHash());
    }

    String toJson() {
        JsonWriter writer = new JsonWriter().beginObject().name("uploadId").value(uploadId)
            .name("version").value(version).name("committedAt");
        if (committedAt == null) writer.nullValue();
        else writer.value(committedAt);
        if (manifestHash != null) writer.name("manifestHash").value(manifestHash);
        return writer.endObject().finish();
    }

    static SnapshotReference read(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(1025);
            if (bytes.length > 1024) throw new IOException("Snapshot index exceeds size limit");
            var reader = new JsonReader(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), 1024, 2);
            String id = null, at = null, hash = null;
            long version = 0;
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                fields.add(field);
                switch (field) {
                    case "uploadId" -> id = reader.nextString();
                    case "version" -> version = reader.nextLong();
                    case "manifestHash" -> hash = reader.nextString();
                    case "committedAt" -> {
                        if (reader.peek() == JsonToken.NULL) reader.nextNull();
                        else at = reader.nextString();
                    }
                    default -> throw new IllegalArgumentException("Unknown snapshot index field");
                }
            }
            reader.endObject();
            reader.finish();
            Set<String> expected = new HashSet<>(Set.of("uploadId", "version", "committedAt"));
            if (hash != null) expected.add("manifestHash");
            if (!fields.equals(expected)) throw new IllegalArgumentException("Missing snapshot index field");
            return new SnapshotReference(id, version, at, hash);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid snapshot index", failure);
        }
    }

}
