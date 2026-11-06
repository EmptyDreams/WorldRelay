package top.kmar.mc.worldreplay.client;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.DirectoryLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.sync.RsyncMode;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SharedWorldSyncTest {

    @TempDir
    Path game;

    private LocalWorldSession stopped() {
        return new LocalWorldSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 2, LocalWorldSession.Stage.STOPPED, "world", 7L, null, null);
    }

    /** 验证已停服世界在持有目录保护锁时能生成固定上传快照，重试复用原快照和上传标识。 */
    @Test
    void onlyStoppedWorldsCanProduceFixedRetryableUploadSnapshots() throws Exception {
        var session = stopped();
        assertFalse(session.with(LocalWorldSession.Stage.PREPARING, null).canOpenCreation());
        assertFalse(session.with(LocalWorldSession.Stage.PREPARING, null).canCancel());
        var store = new LocalWorldSessionStore(game, "https://relay:443", "Alice", UUID.randomUUID(), session.worldId());
        Path world = Files.createDirectory(store.saves(2).resolve("world"));
        Files.writeString(world.resolve("level.dat"), "first-save");
        Files.writeString(world.resolve("session.lock"), "local-lock");
        Files.writeString(Files.createDirectory(world.resolve("mod-data")).resolve("session.lock"), "mod-owned-data");
        assertThrows(IOException.class, () -> SharedWorldSync.prepareUpload(session.with(LocalWorldSession.Stage.STARTED, "world"), store, "a".repeat(64)));
        assertThrows(IOException.class, () -> SharedWorldSync.prepareUpload(session.with(LocalWorldSession.Stage.STOPPING, "world"), store, "a".repeat(64)));
        LocalWorldSession uploading;
        try (DirectoryLock ignored = DirectoryLock.create(world)) {
            uploading = SharedWorldSync.prepareUpload(session, store, "a".repeat(64));
        }
        store.write(uploading);
        assertEquals(uploading, store.read());
        assertEquals(7L, uploading.baseVersion());
        assertFalse(Files.exists(store.transfer(2, "upload").resolve("world/session.lock")));
        Files.writeString(world.resolve("level.dat"), "later-work-copy");
        assertEquals(uploading, SharedWorldSync.prepareUpload(uploading, store, "a".repeat(64)));
        var manifest = SnapshotManifest.read(store.transfer(2, "upload").resolve("manifest.bin"), uploading.manifestHash());
        SnapshotFiles.verify(store.transfer(2, "upload").resolve("world"), manifest);
        assertEquals("first-save", Files.readString(store.transfer(2, "upload").resolve("world/level.dat")));
        assertEquals("mod-owned-data", Files.readString(store.transfer(2, "upload").resolve("world/mod-data/session.lock")));
        assertEquals("later-work-copy", Files.readString(world.resolve("level.dat")));
    }

    /** 验证提交成功响应丢失后可查询确认，无需重新抢锁或启动 rsync。 */
    @Test
    void lostSuccessfulCommitResponseIsConfirmedWithoutStartingRsyncOrReacquiring() throws Exception {
        var session = stopped().uploading(UUID.randomUUID().toString(), "b".repeat(64));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger queries = new AtomicInteger();
        server.createContext("/api/v1/worlds/commit-status", exchange -> {
            try (exchange) {
                String body = queries.incrementAndGet() == 1 ? "{\"pending\":true}" :
                    "{\"worldId\":\"" + session.worldId() + "\",\"epoch\":2,\"uploadId\":\"" + session.uploadId()
                        + "\",\"manifestHash\":\"" + session.manifestHash() + "\",\"version\":8}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(queries.get() == 1 ? 202 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var settings = new ClientConnectionSettings("http://127.0.0.1", server.getAddress().getPort(), "token",
                RsyncMode.CUSTOM, game.resolve("nonexistent-rsync").toString(), game.resolve("nonexistent-ssh").toString());
            var store = new LocalWorldSessionStore(game, settings.serverUri().toString(), "Alice", UUID.randomUUID(), session.worldId());
            SharedWorldSync.upload(game, settings, "Alice", session, store);
            assertEquals(2, queries.get());
            assertFalse(Files.exists(store.temporary().resolve("identity")));
            assertNotNull(store.cache());
        } finally {
            server.stop(0);
        }
    }

}
