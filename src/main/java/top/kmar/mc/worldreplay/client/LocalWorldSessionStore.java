package top.kmar.mc.worldreplay.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 普通单人 saves 之外的会话与工作副本；所有方法仅在串行 IO 队列调用。 */
final class LocalWorldSessionStore {

    private static Path lockedGame;
    private static FileChannel gameLockChannel;
    private static FileLock gameLock;
    private final Path root;
    private final String world;

    static synchronized void lockGame(Path game) throws IOException {
        Path directory = game.toAbsolutePath().normalize().resolve("worldrelay");
        if (directory.equals(lockedGame)) {
            if (gameLockChannel == null || !gameLockChannel.isOpen() || gameLock == null || !gameLock.isValid()) {
                throw new IOException("Client recovery lock is no longer valid");
            }
            return;
        }
        if (lockedGame != null) throw new IOException("Another game directory is active");
        createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(".client.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another client is using the recovery records");
            gameLockChannel = channel;
            gameLock = lock;
            lockedGame = directory;
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("Cannot lock client recovery records");
        }
    }

    LocalWorldSessionStore(Path game, String server, String player, UUID profile, String world) throws IOException {
        this.world = world;
        this.root = location(game, server, player, profile, world);
        createDirectories(root);
    }

    static Path location(Path game, String server, String player, UUID profile, String world) throws IOException {
        if (!UUID.fromString(world).toString().equals(world)) throw new IOException("Invalid world ID");
        String scope;
        try {
            scope = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((server + "\n" + player + "\n" + profile).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        return game.toAbsolutePath().normalize().resolve("worldrelay").resolve(scope).resolve(world);
    }

    Path root() {
        return root;
    }

    Path imported(String id) throws IOException {
        if (!UUID.fromString(id).toString().equals(id)) throw new IOException("Invalid import ID");
        return root.resolve("work").resolve("import-" + id);
    }

    Path saves(long epoch) throws IOException {
        if (epoch < 1) throw new IOException("Invalid epoch");
        Path path = root.resolve("work").resolve(Long.toString(epoch)).resolve("saves");
        createDirectories(path);
        return path;
    }

    Path transfer(long epoch, String direction) throws IOException {
        if (epoch < 1 || (!direction.equals("upload") && !direction.equals("download"))) throw new IOException("Invalid transfer path");
        Path path = root.resolve("work").resolve(Long.toString(epoch)).resolve(direction);
        createDirectories(path);
        return path;
    }

    Path temporary() throws IOException {
        Path path = root.resolve("tmp");
        createDirectories(path);
        return path;
    }

    record Cache(Path payload, String hash, boolean imported) {

    }

    Cache cache() throws IOException {
        Path file = root.resolve("cache.json");
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            JsonObject json = JsonParser.parseString(readBounded(file)).getAsJsonObject();
            String direction = ClientJson.requiredString(json, "direction");
            String hash = ClientJson.requiredString(json, "hash");
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            if (direction.equals("import")) return new Cache(imported(ClientJson.requiredString(json, "id")), hash, true);
            return new Cache(transfer(json.get("epoch").getAsBigDecimal().longValueExact(), direction), hash, false);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid local snapshot cache");
        }
    }

    void cache(LocalWorldSession session, String direction, String hash) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("epoch", session.epoch());
        json.addProperty("direction", direction);
        json.addProperty("hash", hash);
        atomicWrite(root.resolve("cache.json"), json.toString());
    }

    void cacheImported(String id, String hash) throws IOException {
        imported(id);
        JsonObject json = new JsonObject();
        json.addProperty("direction", "import");
        json.addProperty("id", id);
        json.addProperty("hash", hash);
        atomicWrite(root.resolve("cache.json"), json.toString());
    }

    void cleanupAfterImport() {
        try {
            LocalWorldCleanup.clean(root, read(), cache(), true);
        } catch (IOException | RuntimeException failure) {
            WorldRelay.LOGGER.warn("Old imported cache cleanup will retry after the next checkpoint", failure);
        }
    }

    /** 只在新副本完整落盘或提交确认后调用；清理失败不能把已完成的同步报告为失败。 */
    void cleanupAfterCheckpoint() {
        try {
            var session = read();
            var latest = cache();
            if (session != null && session.stage() == LocalWorldSession.Stage.COMMITTED && session.manifestHash() != null
                && (latest == null || !latest.imported())) {
                Path uploaded = root.resolve("work").resolve(Long.toString(session.epoch())).resolve("upload");
                if ((latest == null || !latest.payload().equals(uploaded) || !latest.hash().equals(session.manifestHash()))
                    && Files.isDirectory(uploaded.resolve("world"), LinkOption.NOFOLLOW_LINKS)) {
                    SnapshotManifest.read(uploaded.resolve(SnapshotManifest.FILE_NAME), session.manifestHash());
                    cache(session, "upload", session.manifestHash());
                    latest = cache();
                }
            }
            LocalWorldCleanup.clean(root, session, latest);
        } catch (IOException | RuntimeException failure) {
            WorldRelay.LOGGER.warn("Old local world files could not be removed; cleanup will retry after the next successful sync", failure);
        }
    }

    LocalWorldSession read() throws IOException {
        return readSession(root, world);
    }

    static LocalWorldSession readSession(Path root, String world) throws IOException {
        Path file = root.resolve("session.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            JsonObject json = JsonParser.parseString(readBounded(file)).getAsJsonObject();
            if (json.get("formatVersion").getAsInt() != 1) throw new IllegalArgumentException();
            LocalWorldSession value = new Gson().fromJson(json.get("session"), LocalWorldSession.class);
            if (!world.equals(value.worldId())) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read local recovery record");
        }
    }

    void write(LocalWorldSession session) throws IOException {
        if (!world.equals(session.worldId())) throw new IOException("Wrong recovery record");
        JsonObject json = new JsonObject();
        json.addProperty("formatVersion", 1);
        json.add("session", new Gson().toJsonTree(session));
        atomicWrite(root.resolve("session.json"), json.toString());
    }

    static String device(Path game) throws IOException {
        Path directory = game.toAbsolutePath().normalize().resolve("worldrelay");
        createDirectories(directory);
        Path file = directory.resolve("device.json");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                String id = ClientJson.requiredString(JsonParser.parseString(readBounded(file)).getAsJsonObject(), "deviceId");
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
                return id;
            } catch (RuntimeException failure) {
                throw new IOException("Cannot read device identity");
            }
        }
        String id = UUID.randomUUID().toString();
        JsonObject json = new JsonObject();
        json.addProperty("deviceId", id);
        atomicWrite(file, json.toString());
        return id;
    }

    static void createDirectories(Path path) throws IOException {
        if (path == null) throw new IOException("Managed directory path is required");
        // 先验证已存在的祖先，避免先通过链接写入目录再报错。
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(part)
                || !Files.isDirectory(part, LinkOption.NOFOLLOW_LINKS) || !part.toRealPath().equals(part))) {
                throw new IOException("Managed path must not contain links");
            }
        }
        Files.createDirectories(path);
    }

    private static String readBounded(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Recovery file is not regular");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384) throw new IOException("Recovery record exceeds limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static void atomicWrite(Path target, String value) throws IOException {
        createDirectories(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recovery target is not regular");
        }
        Path temporary = Files.createTempFile(target.getParent(), ".session-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
                while (bytes.hasRemaining()) {
                    // 写入量由缓冲区位置推进记录，循环继续处理部分写入。
                    //noinspection ResultOfMethodCallIgnored
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

}
