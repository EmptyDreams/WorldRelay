package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.DirectoryLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;
import top.kmar.mc.worldreplay.sync.RsyncMode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SharedWorldRecoveryTest {

    @TempDir
    Path game;

    private LocalWorldSession session(LocalWorldSession.Stage stage) {
        return new LocalWorldSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 3, stage, "world", 2L, null, null);
    }

    private LocalWorldSessionStore store(ClientConnectionSettings settings, LocalWorldSession session) throws IOException {
        return new LocalWorldSessionStore(game, settings.serverUri().toString(), "Alice", UUID.randomUUID(), session.worldId());
    }

    private ClientConnectionSettings settings(int port) {
        return new ClientConnectionSettings("http://127.0.0.1", port, "token", RsyncMode.CUSTOM,
            game.resolve("rsync").toString(), game.resolve("ssh").toString());
    }

    private static Path world(LocalWorldSessionStore store, LocalWorldSession session, String levelFile) throws IOException {
        Path world = Files.createDirectory(store.saves(session.epoch()).resolve(session.folder()));
        Files.writeString(world.resolve(SharedPlayerData.MARKER_FILE), "");
        Files.writeString(world.resolve(levelFile), "local-progress");
        Files.writeString(Files.createDirectory(world.resolve("mod-data")).resolve("train.dat"), "local-train");
        return world;
    }

    /** 三种中断阶段都向服务端核对原身份和基础版本，并复用本地进度；level.dat_old 交给原版处理。 */
    @ParameterizedTest
    @EnumSource(value = LocalWorldSession.Stage.class, names = {"START_REQUESTED", "STARTED", "STOPPING"})
    void preparesOriginalLocalWorldWithFencedOwnership(LocalWorldSession.Stage stage) throws Exception {
        var session = session(stage);
        AtomicReference<JsonObject> received = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/worlds/ready", exchange -> {
            try (exchange) {
                received.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                byte[] response = ("{\"worldId\":\"" + session.worldId() + "\",\"sessionId\":\"" + session.sessionId()
                    + "\",\"epoch\":3,\"phase\":\"STARTING\",\"hasSave\":true,\"baseVersion\":2,\"committedVersion\":2,"
                    + "\"heartbeatIntervalSeconds\":5,\"heartbeatTimeoutSeconds\":20}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var settings = settings(server.getAddress().getPort());
            var store = store(settings, session);
            String levelFile = stage == LocalWorldSession.Stage.STOPPING ? "level.dat_old" : "level.dat";
            Path world = world(store, session, levelFile);
            store.write(session);
            var prepared = SharedWorldRecovery.prepare(settings, "Alice", session, store);
            assertEquals(world, prepared.directory());
            assertEquals(5, prepared.heartbeatIntervalSeconds());
            JsonObject expected = WorldOwnershipClient.identity(session);
            WorldOwnershipClient.addBase(expected, 2L);
            expected.addProperty("stage", "RESUME");
            assertEquals(expected, received.get());
            assertEquals(session, store.read());
            assertEquals("local-progress", Files.readString(world.resolve(levelFile)));
            assertEquals("local-train", Files.readString(world.resolve("mod-data/train.dat")));
            try (DirectoryLock lock = DirectoryLock.create(world)) {
                // 后台预检结束后释放目录锁，原版加载器可以重新取得它。
                assertTrue(lock.isValid());
            }
        } finally {
            server.stop(0);
        }
    }

    /** 世界被撤销任期后，恢复必须传播服务端拒绝，保留本地工作副本和恢复记录。 */
    @Test
    void retainsLocalProgressWhenOwnershipWasRevoked() throws Exception {
        var session = session(LocalWorldSession.Stage.STARTED);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/worlds/ready", exchange -> {
            try (exchange) {
                byte[] response = "{\"code\":\"STALE_EPOCH\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(409, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var settings = settings(server.getAddress().getPort());
            var store = store(settings, session);
            Path world = world(store, session, "level.dat");
            store.write(session);
            var failure = assertThrows(WorldOwnershipClient.RequestException.class,
                () -> SharedWorldRecovery.prepare(settings, "Alice", session, store));
            assertEquals("STALE_EPOCH", failure.code());
            assertEquals(session, store.read());
            assertEquals("local-progress", Files.readString(world.resolve("level.dat")));
        } finally {
            server.stop(0);
        }
    }

    /** 缺失、未落盘或仍被占用的本地世界不能发起恢复登记，避免生成空世界或双开。 */
    @Test
    void checksLocalWorldAndDirectoryLockBeforeResuming() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/worlds/ready", exchange -> {
            requests.incrementAndGet();
            exchange.close();
        });
        server.start();
        try {
            var settings = settings(server.getAddress().getPort());
            var session = session(LocalWorldSession.Stage.STOPPING);
            var store = store(settings, session);
            assertThrows(IOException.class, () -> SharedWorldRecovery.prepare(settings, "Alice", session, store));
            Path world = world(store, session, "level.dat");
            Files.delete(world.resolve("level.dat"));
            assertThrows(IOException.class, () -> SharedWorldRecovery.prepare(settings, "Alice", session, store));
            Files.writeString(world.resolve("level.dat"), "local-progress");
            try (DirectoryLock ignored = DirectoryLock.create(world)) {
                assertThrows(IOException.class, () -> SharedWorldRecovery.prepare(settings, "Alice", session, store));
            }
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

}
