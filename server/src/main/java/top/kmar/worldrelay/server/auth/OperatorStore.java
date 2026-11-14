package top.kmar.worldrelay.server.auth;

import top.kmar.worldrelay.permissions.OperatorPolicy;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldAccessException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 独立于世界快照的全局权限；校验后原子发布，失败时不更新内存版本。 */
public final class OperatorStore implements AutoCloseable {
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private OperatorPolicy policy;
    private boolean closed;

    private OperatorStore(Path file, FileChannel channel, FileLock lock, OperatorPolicy policy) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
        this.policy = policy;
    }

    public static OperatorStore open(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path file = path.toAbsolutePath().getParent().toRealPath().resolve(path.getFileName());
        var channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Operator store is already in use");
            OperatorPolicy policy = new OperatorPolicy(0, Map.of());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid operator store file");
                byte[] bytes;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(65537); }
                if (bytes.length > 65536) throw new IOException("Operator store exceeds limit");
                policy = decode(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            }
            return new OperatorStore(file, channel, lock, policy);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw new IOException("Cannot open operator store", failure);
        }
    }

    public synchronized OperatorPolicy snapshot() {
        ensureOpen();
        return policy;
    }

    public synchronized OperatorPolicy update(long expectedRevision, String username, int level) throws IOException, WorldAccessException {
        ensureOpen();
        UserStore.validateUsername(username);
        if (level < 0 || level > 4) throw new IllegalArgumentException("Invalid OP level");
        if (expectedRevision != policy.revision())
            throw new WorldAccessException(409, "OPERATOR_REVISION_MISMATCH", "Refresh the operator policy before editing");
        var entries = new TreeMap<>(policy.operators());
        if (level == 0) entries.remove(username); else entries.put(username, level);
        if (entries.equals(policy.operators())) return policy;
        var next = new OperatorPolicy(Math.incrementExact(policy.revision()), entries);
        byte[] bytes = json(next).getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), ".operators-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    // 部分写入通过缓冲区位置继续处理，不依赖单次返回的字节数。
                    //noinspection ResultOfMethodCallIgnored
                    output.write(buffer);
                }
                output.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        policy = next;
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
            "Global operator updated by local-admin: username={0}, level={1}, revision={2}", username, level, next.revision());
        return next;
    }

    public static void write(JsonWriter writer, OperatorPolicy policy) {
        writer.name("revision").value(policy.revision()).name("operators").beginArray();
        new TreeMap<>(policy.operators()).forEach((name, level) -> writer.beginObject()
            .name("username").value(name).name("level").value(level).endObject());
        writer.endArray();
    }

    private static String json(OperatorPolicy policy) {
        var writer = new JsonWriter().beginObject().name("formatVersion").value(1);
        write(writer, policy);
        return writer.endObject().finish();
    }

    private static OperatorPolicy decode(String json) throws IOException {
        try {
            var reader = new JsonReader(json, 65536, 4);
            long revision = -1;
            int format = 0;
            var seen = new HashSet<String>();
            Map<String, Integer> entries = new TreeMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!seen.add(field)) throw new IllegalArgumentException("Duplicate field");
                switch (field) {
                    case "formatVersion" -> format = reader.nextInt();
                    case "revision" -> revision = reader.nextLong();
                    case "operators" -> {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            reader.beginObject();
                            String name = null;
                            int level = 0;
                            var fields = new HashSet<String>();
                            while (reader.hasNext()) {
                                String key = reader.nextName();
                                if (!fields.add(key)) throw new IllegalArgumentException("Duplicate entry field");
                                if (key.equals("username")) name = reader.nextString();
                                else if (key.equals("level")) level = reader.nextInt();
                                else throw new IllegalArgumentException("Unknown entry field");
                            }
                            reader.endObject();
                            if (name == null || entries.putIfAbsent(name, level) != null || entries.size() > OperatorPolicy.MAX_ENTRIES)
                                throw new IllegalArgumentException("Invalid operators");
                        }
                        reader.endArray();
                    }
                    default -> throw new IllegalArgumentException("Unknown field");
                }
            }
            reader.endObject();
            reader.finish();
            if (format != 1 || !seen.equals(Set.of("formatVersion", "revision", "operators")))
                throw new IllegalArgumentException("Invalid operator store");
            return new OperatorPolicy(revision, entries);
        } catch (RuntimeException failure) { throw new IOException("Invalid operator store", failure); }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { channel.close(); }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Operator store is closed");
    }
}
