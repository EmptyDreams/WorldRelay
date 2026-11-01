package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.protocol.WorldSummary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 每个世界一个完整目录，无目录外世界索引；只在启动时扫描，手动删除须先停服。 */
public final class WorldStore implements AutoCloseable {

    private static final int MAX_METADATA_BYTES = 4096;
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final DirectoryPublisher publisher;
    private final Supplier<UUID> idGenerator;
    private final Map<String, WorldSummary> worlds = new TreeMap<>();
    private final Set<String> names = new HashSet<>();
    private boolean closed;

    private WorldStore(
        Path root, FileChannel lockChannel, FileLock fileLock, DirectoryPublisher publisher,
        Supplier<UUID> idGenerator
    ) {
        this.root = root;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
        this.publisher = publisher;
        this.idGenerator = idGenerator;
    }

    public static WorldStore open(Path directory) throws IOException {
        return open(directory, (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    // 允许存储测试模拟不支持原子目录发布的文件系统，不引入运行时反射或存储框架。
    static WorldStore open(Path directory, DirectoryPublisher publisher) throws IOException {
        return open(directory, publisher, UUID::randomUUID);
    }

    // 显式提供 ID 来源，使测试可以确定性验证 UUID 碰撞时不会覆盖已有世界。
    static WorldStore open(Path directory, DirectoryPublisher publisher, Supplier<UUID> idGenerator) throws IOException {
        Files.createDirectories(directory.toAbsolutePath().normalize());
        Path root = directory.toRealPath();
        Path lockFile = root.resolve(".lock");
        if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            requireLocalPath(lockFile, false);
        }
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("World store is already in use");
            }
            if (lock == null) {
                throw new IOException("World store is already in use");
            }
            WorldStore store = new WorldStore(root, channel, lock, publisher, idGenerator);
            store.load();
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public synchronized List<WorldSummary> list() {
        ensureOpen();
        return List.copyOf(worlds.values());
    }

    /**
     * 整个创建流程在实例锁内串行执行，包含重名检查、ID 生成、目录发布和内存更新。
     * 仅在发布成功后使记录可见并占用名称；返回 null 表示名称已存在，不覆盖已有世界。
     */
    public synchronized WorldSummary create(String name) throws IOException {
        ensureOpen();
        validateName(name);
        if (names.contains(name)) {
            return null;
        }
        String id;
        do {
            id = idGenerator.get().toString();
        } while (worlds.containsKey(id) || Files.exists(root.resolve(id), LinkOption.NOFOLLOW_LINKS));
        WorldSummary world = summary(id, name);
        String json = new JsonWriter().beginObject()
            .name("formatVersion").value(1)
            .name("worldId").value(id)
            .name("name").value(name)
            .name("createdAt").value(Instant.now().toString())
            .endObject().finish();
        Path temporary = Files.createTempDirectory(root, ".creating-");
        try {
            try (FileChannel output = FileChannel.open(temporary.resolve("world.json"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    //noinspection ResultOfMethodCallIgnored
                    output.write(buffer);
                }
                output.force(true);
            }
            // 不降级为非原子复制；失败不会发布半份世界记录。
            publisher.publish(temporary, root.resolve(id));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                // 只清理本次创建的两个已知路径，不递归删除任何已有世界。
                Files.deleteIfExists(temporary.resolve("world.json"));
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        worlds.put(id, world);
        names.add(name);
        return world;
    }

    public static void validateName(String name) {
        if (name == null || name.isBlank() || !name.equals(name.strip())
            || name.codePointCount(0, name.length()) > 64
            || name.codePoints().anyMatch(c -> Character.isISOControl(c)
            || Character.getType(c) == Character.SURROGATE)) {
            throw new IllegalArgumentException("Invalid world name");
        }
    }

    private void load() throws IOException {
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                String id = directory.getFileName().toString();
                // .lock、未完成的 .creating-* 及非世界目录均不作为记录。
                if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    continue;
                }
                requireLocalPath(directory, true);
                Path metadata = directory.resolve("world.json");
                requireLocalPath(metadata, false);
                byte[] bytes;
                try (var input = Files.newInputStream(metadata, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
                }
                if (bytes.length > MAX_METADATA_BYTES) {
                    throw new IOException("World metadata exceeds size limit: " + id);
                }
                try {
                    String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                    JsonReader reader = new JsonReader(json, MAX_METADATA_BYTES, 4);
                    reader.beginObject();
                    int format = 0;
                    String worldId = null;
                    String name = null;
                    String createdAt = null;
                    while (reader.hasNext()) {
                        switch (reader.nextName()) {
                            case "formatVersion" -> format = reader.nextInt();
                            case "worldId" -> worldId = reader.nextString();
                            case "name" -> name = reader.nextString();
                            case "createdAt" -> createdAt = reader.nextString();
                            default -> throw new IllegalArgumentException("Unknown world metadata field");
                        }
                    }
                    reader.endObject();
                    reader.finish();
                    if (format != 1 || !id.equals(worldId) || createdAt == null) {
                        throw new IllegalArgumentException("Invalid world metadata");
                    }
                    validateName(name);
                    Instant.parse(createdAt);
                    if (worlds.containsKey(id)) {
                        throw new IOException("Duplicate world ID: " + id);
                    }
                    if (!names.add(name)) {
                        throw new IOException("Duplicate world name in metadata: " + id);
                    }
                    worlds.put(id, summary(id, name));
                } catch (IllegalArgumentException | DateTimeException failure) {
                    throw new IOException("Invalid world metadata: " + id);
                }
            }
        }
    }

    /** 拒绝符号链接、junction 或其他实际指向不同位置的路径。父目录已逐级校验。 */
    private static void requireLocalPath(Path path, boolean directory) throws IOException {
        boolean correctType = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!correctType || Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) {
            throw new IOException("World store path must be a local " + (directory ? "directory" : "file") + ": " + path);
        }
    }

    private static WorldSummary summary(String id, String name) {
        return new WorldSummary(id, name, "FREE", null, 0, null);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("World store is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            fileLock.release();
        } finally {
            lockChannel.close();
        }
    }

    @FunctionalInterface
    interface DirectoryPublisher {

        void publish(Path source, Path target) throws IOException;

    }

}