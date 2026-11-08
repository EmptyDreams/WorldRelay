package top.kmar.worldrelay.server.tunnel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldStore;
import top.kmar.worldrelay.tunnel.TunnelConnector;
import top.kmar.worldrelay.tunnel.TunnelEndpoint;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class TunnelGatewayTest {

    @TempDir
    Path temporary;

    @SuppressWarnings("resource") // connector 与 socket 的生命周期由 resources 集合统一管理。
    private final class Fixture implements AutoCloseable {

        final WorldStore worlds;
        final TunnelGateway gateway;
        final AtomicLong clock = new AtomicLong(System.nanoTime());
        final List<AutoCloseable> resources = new ArrayList<>();
        final TunnelConfig.Mode mode;

        Fixture(TunnelConfig.Mode mode) throws Exception {
            this.mode = mode;
            worlds = WorldStore.open(temporary.resolve(UUID.randomUUID().toString()));
            var config = new TunnelConfig(mode, "127.0.0.1", 0, "127.0.0.1", 0, temporary.resolve("tls"));
            gateway = new TunnelGateway(worlds, config, clock::get);
            gateway.start();
        }

        record World(String id, WorldStore.Identity owner) {

        }

        World world() throws Exception {
            String id = Objects.requireNonNull(worlds.create(UUID.randomUUID().toString())).worldId();
            String device = UUID.randomUUID().toString();
            var acquired = worlds.acquire(id, "Host", device, UUID.randomUUID().toString()).view();
            var owner = new WorldStore.Identity("Host", device, acquired.sessionId(), acquired.epoch());
            worlds.ready(id, owner, WorldStore.ReadyStage.SAVE_SYNCED, null, null);
            return new World(id, owner);
        }

        TunnelConnector connector() {
            var connector = new TunnelConnector();
            resources.add(connector);
            return connector;
        }

        TunnelEndpoint endpoint(TunnelGateway.Grant grant) {
            JsonWriter writer = new JsonWriter();
            gateway.describe(writer, grant);
            JsonReader reader = new JsonReader(writer.finish());
            String fingerprint = null;
            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.nextName().equals("fingerprint")) fingerprint = reader.nextString();
                else reader.skipValue();
            }
            reader.endObject();
            reader.finish();
            return new TunnelEndpoint(URI.create((mode == TunnelConfig.Mode.WSS ? "ws" : "tls") + "://127.0.0.1:" + gateway.address().getPort()
                + (mode == TunnelConfig.Mode.WSS ? "/api/v1/tunnel" : "")), fingerprint);
        }

        TunnelConnector.Session host(World world, int localPort) throws Exception {
            var grant = gateway.host(world.id, world.owner);
            var endpoint = endpoint(grant);
            var connector = connector();
            var control = connector.connect(endpoint, grant.token(), message -> {
                String token = message.split("\n", -1)[0];
                connector.local(localPort).thenCompose(local -> connector.connect(endpoint, token, null)
                    .whenComplete((session, error) -> {
                        if (error != null) {
                            try {
                                local.close();
                            } catch (java.io.IOException ignored) { /* 对端已关闭。 */ }
                        } else session.bindRaw(local);
                    }));
            }).get(10, TimeUnit.SECONDS);
            gateway.ready(world.id, world.owner, control.registrationId());
            return control;
        }

        Socket guest(World world) throws Exception {
            var grant = gateway.join(world.id, "Guest", false);
            var connector = connector();
            var session = connector.connect(endpoint(grant), grant.token(), null).get(10, TimeUnit.SECONDS);
            var address = connector.listen(session).get(10, TimeUnit.SECONDS);
            Socket socket = new Socket();
            socket.connect(address, 10_000);
            socket.setSoTimeout(10_000);
            resources.add(socket);
            return socket;
        }

        @Override
        public void close() throws Exception {
            for (int i = resources.size() - 1; i >= 0; i--) {
                resources.get(i).close();
            }
            gateway.close();
            worlds.close();
        }

    }

    private static final class Echo implements AutoCloseable {

        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        Echo(int mask) throws Exception {
            jobs.submit(() -> {
                try {
                    while (!listener.isClosed()) {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        jobs.submit(() -> {
                            try (socket) {
                                byte[] buffer = new byte[8192];
                                int count;
                                while ((count = socket.getInputStream().read(buffer)) >= 0) {
                                    for (int i = 0; i < count; i++) {
                                        buffer[i] ^= (byte) mask;
                                    }
                                    socket.getOutputStream().write(buffer, 0, count);
                                }
                            } catch (java.io.IOException ignored) { /* 对端主动关闭。 */ } finally {
                                sockets.remove(socket);
                            }
                        });
                    }
                } catch (java.io.IOException ignored) { /* 测试关闭监听器。 */ }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
            jobs.shutdownNow();
        }

    }

    /** 两种传输均通过真实 Socket 并发转发不同世界的大数据，验证配对隔离、顺序和背压。 */
    @ParameterizedTest
    @EnumSource(TunnelConfig.Mode.class)
    void forwardsConcurrentWorldsWithoutMixingStreams(TunnelConfig.Mode mode) throws Exception {
        try (var fixture = new Fixture(mode); var first = new Echo(0x35); var second = new Echo(0x6a); var jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            var worldA = fixture.world();
            var worldB = fixture.world();
            fixture.host(worldA, first.port());
            fixture.host(worldB, second.port());
            try (Socket a = fixture.guest(worldA); Socket b = fixture.guest(worldB)) {
                // 持续传输多轮，让可写/不可写的跨事件循环切换反复发生。
                for (int round = 0; round < 4; round++) {
                    byte[] data = new byte[4 * 1024 * 1024];
                    new Random(42).nextBytes(data);
                    var writeA = jobs.submit(() -> {
                        a.getOutputStream().write(data);
                        return null;
                    });
                    var writeB = jobs.submit(() -> {
                        b.getOutputStream().write(data);
                        return null;
                    });
                    var readA = jobs.submit(() -> a.getInputStream().readNBytes(data.length));
                    var readB = jobs.submit(() -> b.getInputStream().readNBytes(data.length));
                    byte[] expectedA = data.clone(), expectedB = data.clone();
                    for (int i = 0; i < data.length; i++) {
                        expectedA[i] ^= 0x35;
                        expectedB[i] ^= 0x6a;
                    }
                    assertArrayEquals(expectedA, readA.get(15, TimeUnit.SECONDS));
                    assertArrayEquals(expectedB, readB.get(15, TimeUnit.SECONDS));
                    writeA.get(5, TimeUnit.SECONDS);
                    writeB.get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    /** 注册票据只能使用一次且会过期，错误任期和被新登记替换的旧票据无法使用。 */
    @ParameterizedTest
    @EnumSource(TunnelConfig.Mode.class)
    void scopesAndExpiresTickets(TunnelConfig.Mode mode) throws Exception {
        try (var fixture = new Fixture(mode)) {
            var world = fixture.world();
            var grant = fixture.gateway.host(world.id, world.owner);
            var connector = fixture.connector();
            var endpoint = fixture.endpoint(grant);
            var control = connector.connect(endpoint, grant.token(), ignored -> {}).get(10, TimeUnit.SECONDS);
            fixture.gateway.ready(world.id, world.owner, control.registrationId());
            assertThrows(ExecutionException.class, () -> connector.connect(endpoint, grant.token(), null).get(10, TimeUnit.SECONDS));
            var expired = fixture.gateway.host(world.id, world.owner);
            fixture.clock.addAndGet(TimeUnit.SECONDS.toNanos(61));
            assertThrows(ExecutionException.class, () -> connector.connect(endpoint, expired.token(), null).get(10, TimeUnit.SECONDS));
            var stale = new WorldStore.Identity("Host", world.owner.deviceId(), world.owner.sessionId(), world.owner.epoch() + 1);
            assertEquals("STALE_EPOCH", assertThrows(WorldAccessException.class, () -> fixture.gateway.host(world.id, stale)).code());
            var older = fixture.gateway.host(world.id, world.owner);
            var latest = fixture.gateway.host(world.id, world.owner);
            var newControl = connector.connect(endpoint, latest.token(), ignored -> {}).get(10, TimeUnit.SECONDS);
            fixture.gateway.ready(world.id, world.owner, newControl.registrationId());
            assertThrows(ExecutionException.class, () -> connector.connect(endpoint, older.token(), null).get(10, TimeUnit.SECONDS));
            assertEquals("RUNNING", fixture.worlds.list().getFirst().status());
        }
    }

    /** 控制通道重连会废止旧的待配对连接与票据，新的登记仍能正常接受访客。 */
    @ParameterizedTest
    @EnumSource(TunnelConfig.Mode.class)
    void replacesRegistrationAndRejectsLateAttachments(TunnelConfig.Mode mode) throws Exception {
        try (var fixture = new Fixture(mode); var echo = new Echo(0)) {
            var world = fixture.world();
            var oldGrant = fixture.gateway.host(world.id, world.owner);
            var oldConnector = fixture.connector();
            var attach = new CompletableFuture<String>();
            var old = oldConnector.connect(fixture.endpoint(oldGrant), oldGrant.token(), message -> attach.complete(message.split("\n")[0])).get(10, TimeUnit.SECONDS);
            fixture.gateway.ready(world.id, world.owner, old.registrationId());
            var join = fixture.gateway.join(world.id, "Guest", false);
            var waiting = fixture.connector().connect(fixture.endpoint(join), join.token(), null);
            String oldAttach = attach.get(10, TimeUnit.SECONDS);
            fixture.host(world, echo.port());
            assertThrows(ExecutionException.class, () -> oldConnector.connect(fixture.endpoint(join), oldAttach, null).get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> waiting.get(10, TimeUnit.SECONDS));
            try (Socket guest = fixture.guest(world)) {
                guest.getOutputStream().write(new byte[]{1, 2, 3});
                assertArrayEquals(new byte[]{1, 2, 3}, guest.getInputStream().readNBytes(3));
            }
        }
    }

    /** 隧道失联与停服均撤下入口但保留持有者，原房主可以在同一任期重新登记。 */
    @ParameterizedTest
    @EnumSource(TunnelConfig.Mode.class)
    void retainsOwnershipAcrossDisconnectAndStopsNewJoins(TunnelConfig.Mode mode) throws Exception {
        try (var fixture = new Fixture(mode); var echo = new Echo(0)) {
            var world = fixture.world();
            var control = fixture.host(world, echo.port());
            fixture.worlds.tunnelOffline(world.id, world.owner);
            assertEquals("RECOVERY_REQUIRED", fixture.worlds.heartbeat(world.id, world.owner, 1).status());
            fixture.gateway.ready(world.id, world.owner, control.registrationId());
            assertEquals("RUNNING", fixture.worlds.list().getFirst().status());
            control.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fixture.worlds.list().getFirst().status().equals("RUNNING") && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals("RECOVERY_REQUIRED", fixture.worlds.list().getFirst().status());
            assertThrows(WorldAccessException.class, () -> fixture.worlds.acquire(world.id, "Guest", UUID.randomUUID().toString(), UUID.randomUUID().toString()));
            fixture.host(world, echo.port());
            assertEquals("RUNNING", fixture.worlds.list().getFirst().status());
            fixture.worlds.stop(world.id, world.owner, WorldStore.StopStage.BEGIN, null);
            assertThrows(WorldAccessException.class, () -> fixture.gateway.join(world.id, "Guest", false));
            assertEquals("STOPPING", fixture.worlds.list().getFirst().status());
            assertEquals(world.owner.epoch(), fixture.worlds.heartbeat(world.id, world.owner, 1).epoch());
        }
    }

    /** 管理员放弃会立即废止旧房主登记及未使用的加入票据，新房主仍可从新任期接手。 */
    @ParameterizedTest
    @EnumSource(TunnelConfig.Mode.class)
    void abandonmentInvalidatesHostRegistrationAndPendingTickets(TunnelConfig.Mode mode) throws Exception {
        try (var fixture = new Fixture(mode); var echo = new Echo(0)) {
            var world = fixture.world();
            fixture.host(world, echo.port());
            var join = fixture.gateway.join(world.id, "Guest", false);
            var host = fixture.gateway.host(world.id, world.owner);
            var preview = fixture.worlds.previewAbandon(world.id);
            var result = fixture.worlds.abandon(preview);
            assertEquals(world.owner.epoch() + 1, result.epoch());
            assertEquals("FREE", fixture.worlds.list().getFirst().status());
            var connector = fixture.connector();
            assertThrows(ExecutionException.class, () -> connector.connect(fixture.endpoint(join), join.token(), null).get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> connector.connect(fixture.endpoint(host), host.token(), ignored -> {}).get(10, TimeUnit.SECONDS));
            assertThrows(WorldAccessException.class, () -> fixture.gateway.join(world.id, "Guest", false));
            var next = fixture.worlds.acquire(world.id, "Next", UUID.randomUUID().toString(), UUID.randomUUID().toString()).view();
            assertTrue(next.epoch() > result.epoch());
            assertEquals(result, fixture.worlds.abandon(preview));
            assertEquals("Next", fixture.worlds.list().getFirst().hostName());
        }
    }

    /** TCP 可以通过域名连接并验证 API 指纹；错误指纹拒绝握手，重启继续使用同一证书。 */
    @Test
    void verifiesPinnedCertificateAndPersistsIdentity() throws Exception {
        String fingerprint;
        try (var fixture = new Fixture(TunnelConfig.Mode.TCP)) {
            var world = fixture.world();
            var grant = fixture.gateway.host(world.id, world.owner);
            var endpoint = fixture.endpoint(grant);
            fingerprint = endpoint.fingerprint();
            var connector = fixture.connector();
            var wrong = new TunnelEndpoint(endpoint.uri(), "0".repeat(64));
            assertThrows(ExecutionException.class, () -> connector.connect(wrong, grant.token(), null).get(10, TimeUnit.SECONDS));
            var domain = new TunnelEndpoint(new URI("tls", null, "localhost", endpoint.port(), null, null, null), fingerprint);
            assertNotNull(connector.connect(domain, grant.token(), ignored -> {}).get(10, TimeUnit.SECONDS).registrationId());
        }
        assertEquals(fingerprint, new TunnelIdentity(temporary.resolve("tls")).fingerprint);
    }

    /** 本机服务发出最后一段数据后立即关闭时，两种隧道都须先交付数据再传播关闭。 */
    @ParameterizedTest
    @EnumSource(TunnelConfig.Mode.class)
    void flushesFinalBytesBeforePropagatingClose(TunnelConfig.Mode mode) throws Exception {
        byte[] finalBytes = new byte[2 * 1024 * 1024];
        new Random(9).nextBytes(finalBytes);
        try (var fixture = new Fixture(mode); var listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
             var jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            var serving = jobs.submit(() -> {
                try (Socket socket = listener.accept()) {
                    assertEquals(1, socket.getInputStream().read());
                    socket.getOutputStream().write(finalBytes);
                }
                return null;
            });
            var world = fixture.world();
            fixture.host(world, listener.getLocalPort());
            try (Socket guest = fixture.guest(world)) {
                guest.getOutputStream().write(1);
                assertArrayEquals(finalBytes, guest.getInputStream().readNBytes(finalBytes.length));
                assertEquals(-1, guest.getInputStream().read());
            }
            serving.get(10, TimeUnit.SECONDS);
        }
    }

}
