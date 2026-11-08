package top.kmar.mc.worldreplay.client;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.DirectoryLock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;
import top.kmar.mc.worldreplay.sync.RsyncMode;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalWorldCleanupTest {

    @TempDir
    Path game;
    private final String world = id();
    private final UUID profile = UUID.randomUUID();

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private LocalWorldSession session(long epoch, LocalWorldSession.Stage stage) {
        return new LocalWorldSession(world, id(), id(), id(), epoch, stage, "world", 7L, id(), "a".repeat(64));
    }

    private LocalWorldSessionStore store(String server, String world) throws IOException {
        return new LocalWorldSessionStore(game, server, "Dev", profile, world);
    }

    private static Path saved(LocalWorldSessionStore store, long epoch, String content) throws IOException {
        Path world = Files.createDirectory(store.saves(epoch).resolve("world"));
        Files.writeString(world.resolve(SharedPlayerData.MARKER_FILE), "");
        Files.writeString(world.resolve("level.dat"), content);
        Files.writeString(Files.createDirectory(world.resolve("mod-data")).resolve("train.dat"), content + "-train");
        return world;
    }

    private static String payload(LocalWorldSessionStore store, long epoch, String direction, Path source) throws IOException {
        Path payload = store.transfer(epoch, direction);
        SnapshotFiles.copyIsolated(source, payload.resolve("world"), false);
        byte[] manifest = SnapshotFiles.scan(payload.resolve("world")).encode();
        Files.write(payload.resolve("manifest.bin"), manifest);
        return SnapshotManifest.hash(manifest);
    }

    /** 提交成功后只留下最新增量缓存，清除旧任期、当前工作目录和多余临时副本，其他世界不受影响。 */
    @Test
    void keepsOnlyLatestCacheAfterCommit() throws Exception {
        var store = store("https://relay:443", world);
        Path older = saved(store, 1, "old-progress");
        payload(store, 1, "upload", older);
        Path current = saved(store, 2, "committed-progress");
        payload(store, 2, "download", current);
        String hash = payload(store, 2, "upload", current);
        Path temporary = Files.createDirectory(store.saves(2).getParent().resolve(".upload-incomplete"));
        Files.writeString(temporary.resolve("partial"), "partial-copy");
        var other = store("https://relay:443", id());
        Path otherWorld = saved(other, 1, "unrelated-world");
        var committed = session(2, LocalWorldSession.Stage.COMMITTED).uploading(id(), hash).with(LocalWorldSession.Stage.COMMITTED, "world");
        store.cache(committed, "upload", hash);
        store.write(committed);
        store.cleanupAfterCheckpoint();
        assertEquals(committed, store.read());
        assertFalse(Files.exists(older.getParent().getParent()));
        assertFalse(Files.exists(current.getParent()));
        assertFalse(Files.exists(temporary));
        Path epoch = store.transfer(2, "upload").getParent();
        try (var children = Files.list(epoch)) {
            assertEquals(java.util.List.of("upload"), children.map(path -> path.getFileName().toString()).toList());
        }
        assertEquals("committed-progress", Files.readString(epoch.resolve("upload/world/level.dat")));
        assertEquals("unrelated-world", Files.readString(otherWorld.resolve("level.dat")));
        var cache = store.cache();
        assertNotNull(cache);
        SnapshotFiles.verify(cache.payload().resolve("world"), SnapshotManifest.read(cache.payload().resolve("manifest.bin"), cache.hash()));
    }

    /** 下载目标校验失败时保留失败存档和旧缓存，完整新副本成功后才替换索引并删除旧数据。 */
    @Test
    void clearsFailedCopiesOnlyAfterNewDownloadIsComplete() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var settings = new ClientConnectionSettings("http://127.0.0.1", server.getAddress().getPort(), "token", RsyncMode.CUSTOM,
            game.resolve("rsync").toString(), game.resolve("ssh").toString());
        var store = store(settings.serverUri().toString(), world);
        Path failed = saved(store, 1, "failed-progress");
        var oldSession = session(1, LocalWorldSession.Stage.UPLOADING);
        String oldHash = payload(store, 1, "upload", failed);
        store.cache(oldSession, "upload", oldHash);
        Path current = saved(store, 2, "server-progress");
        String hash = payload(store, 2, "download", current);
        Files.writeString(current.resolve("level.dat"), "incorrect-copy");
        var downloading = session(2, LocalWorldSession.Stage.DOWNLOADING);
        store.write(downloading);
        server.createContext("/api/v1/worlds/download", exchange -> {
            try (exchange) {
                String json = "{\"worldId\":\"" + world + "\",\"epoch\":2,\"uploadId\":\"" + downloading.sessionId() + "\",\"manifestHash\":\"" + hash + "\"}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.createContext("/api/v1/worlds/close-download", exchange -> {
            try (exchange) {
                byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            assertThrows(IOException.class, () -> SharedWorldSync.download(game, settings, "Dev", downloading, store));
            assertEquals("failed-progress", Files.readString(failed.resolve("level.dat")));
            var oldCache = store.cache();
            assertNotNull(oldCache);
            assertEquals(oldHash, oldCache.hash());
            Files.writeString(current.resolve("level.dat"), "server-progress");
            assertEquals(current, SharedWorldSync.download(game, settings, "Dev", downloading, store));
            assertFalse(Files.exists(failed.getParent().getParent()));
            var newCache = store.cache();
            assertNotNull(newCache);
            assertEquals(hash, newCache.hash());
            assertEquals("server-progress", Files.readString(current.resolve("level.dat")));
        } finally {
            server.stop(0);
        }
    }

    /** 上传重试期间保留工作存档及固定上传身份，提交确认后重新发布最新缓存并清除旧副本。 */
    @Test
    void protectsRetryDataAndCleansItAfterSuccessfulCommit() throws Exception {
        var store = store("https://relay:443", world);
        Path older = saved(store, 1, "cached-progress");
        String oldHash = payload(store, 1, "upload", older);
        store.cache(session(1, LocalWorldSession.Stage.COMMITTED), "upload", oldHash);
        Path current = saved(store, 2, "retry-progress");
        String hash = payload(store, 2, "upload", current);
        var uploading = session(2, LocalWorldSession.Stage.UPLOADING).uploading(id(), hash);
        store.write(uploading);
        SharedWorldSync.cacheSnapshot(store, uploading);
        store.cleanupAfterCheckpoint();
        assertEquals(uploading, store.read());
        assertEquals("retry-progress", Files.readString(current.resolve("level.dat")));
        assertEquals("retry-progress", Files.readString(store.transfer(2, "upload").resolve("world/level.dat")));
        assertFalse(Files.exists(older.getParent().getParent()));
        var committed = uploading.with(LocalWorldSession.Stage.COMMITTED, "world");
        store.write(committed);
        store.cleanupAfterCheckpoint();
        assertFalse(Files.exists(current.getParent()));
        assertFalse(Files.exists(older.getParent().getParent()));
        var cache = store.cache();
        assertNotNull(cache);
        assertEquals(hash, cache.hash());
        assertEquals(committed, store.read());
    }

    /** 清理不会删除仍被 Minecraft 目录锁占用的旧世界，解除占用后下一次清理再移除。 */
    @Test
    void skipsAnOpenWorldUntilItsDirectoryLockIsReleased() throws Exception {
        var store = store("https://relay:443", world);
        Path old = saved(store, 1, "open-progress");
        Path current = saved(store, 2, "committed-progress");
        String hash = payload(store, 2, "upload", current);
        var committed = session(2, LocalWorldSession.Stage.COMMITTED).uploading(id(), hash).with(LocalWorldSession.Stage.COMMITTED, "world");
        store.cache(committed, "upload", hash);
        store.write(committed);
        try (DirectoryLock ignored = DirectoryLock.create(old)) {
            store.cleanupAfterCheckpoint();
            assertEquals("open-progress", Files.readString(old.resolve("level.dat")));
        }
        store.cleanupAfterCheckpoint();
        assertFalse(Files.exists(old.getParent().getParent()));
        assertEquals("committed-progress", Files.readString(store.transfer(2, "upload").resolve("world/level.dat")));
    }

    /** 旧目录包含指向受管范围外的链接时不递归进入，外部文件保持原样。 */
    @Test
    void doesNotFollowLinksOutsideTheManagedWorld() throws Exception {
        var store = store("https://relay:443", world);
        Path external = Files.createDirectory(game.resolve("external"));
        Files.writeString(external.resolve("keep"), "outside-progress");
        Path old = saved(store, 1, "old-progress");
        Path link = old.getParent().resolve("alias");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException failure) {
            Assumptions.abort("Symbolic links are unavailable in this environment");
        }
        Path current = saved(store, 2, "committed-progress");
        String hash = payload(store, 2, "upload", current);
        var committed = session(2, LocalWorldSession.Stage.COMMITTED).uploading(id(), hash).with(LocalWorldSession.Stage.COMMITTED, "world");
        store.cache(committed, "upload", hash);
        store.write(committed);
        store.cleanupAfterCheckpoint();
        assertEquals("outside-progress", Files.readString(external.resolve("keep")));
        Files.delete(link);
        store.cleanupAfterCheckpoint();
        assertFalse(Files.exists(old.getParent().getParent()));
    }

}
