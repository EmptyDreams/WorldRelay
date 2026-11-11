package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.kmar.mc.worldreplay.sync.RsyncMode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class WorldOwnershipClientTest {

    private final String world = UUID.randomUUID().toString();
    private final String sessionId = UUID.randomUUID().toString();

    private LocalWorldSession acquiring() {
        return new LocalWorldSession(world, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            null, 0, LocalWorldSession.Stage.ACQUIRING, null);
    }

    private ClientConnectionSettings settings(HttpServer server) {
        return new ClientConnectionSettings("http://127.0.0.1", server.getAddress().getPort(), "test-token",
            RsyncMode.AUTO, "", "");
    }

    private String response() {
        return "{\"worldId\":\"" + world + "\",\"sessionId\":\"" + sessionId
            + "\",\"epoch\":1,\"phase\":\"PREPARING\",\"hasSave\":false,\"baseVersion\":null,"
            + "\"committedVersion\":null,\"heartbeatIntervalSeconds\":10,\"heartbeatTimeoutSeconds\":30}";
    }

    /** 获取所有权响应提供创建时固定的登录模式，供房主初始化内置服务器使用。 */
    @Test
    void readsFixedLoginModeFromAcquiredOwnership() throws Exception {
        String body = response().replace("\"hasSave\":false", "\"hasSave\":false,\"allowOfflinePlayers\":true");
        assertTrue(WorldOwnershipClient.parse(body).allowOfflinePlayers());
        assertFalse(WorldOwnershipClient.parse(body.replace("\"allowOfflinePlayers\":true", "\"allowOfflinePlayers\":false")).allowOfflinePlayers());
        assertThrows(IOException.class, () -> WorldOwnershipClient.parse(body.replace("\"allowOfflinePlayers\":true", "\"allowOfflinePlayers\":1")));
    }

    /** 获取失败重试保持请求标识，取消成功但响应失败后重试仍使用原持有关系。 */
    @Test
    void retriesReuseAcquisitionAndCancellationIdentity() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonObject> acquisitions = new CopyOnWriteArrayList<>(), cancellations = new CopyOnWriteArrayList<>();
        AtomicInteger acquisitionCount = new AtomicInteger(), cancellationCount = new AtomicInteger();
        server.createContext("/api/v1/worlds/acquire", exchange -> {
            try (exchange) {
                acquisitions.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                byte[] body = response().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(acquisitionCount.incrementAndGet() == 1 ? 503 : 201, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/api/v1/worlds/cancel-creation", exchange -> {
            try (exchange) {
                cancellations.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                // 模拟服务端已完成取消，但客户端第一次收到错误响应。
                exchange.sendResponseHeaders(cancellationCount.incrementAndGet() == 1 ? 500 : 204, -1);
            }
        });
        server.start();
        try {
            var settings = settings(server);
            var request = acquiring();
            assertThrows(IOException.class, () -> WorldOwnershipClient.acquire(settings, "房主", request));
            var owned = request.owned(WorldOwnershipClient.acquire(settings, "房主", request));
            assertEquals(acquisitions.getFirst(), acquisitions.getLast());
            assertThrows(IOException.class, () -> WorldOwnershipClient.cancelCreation(settings, "房主", owned));
            assertDoesNotThrow(() -> WorldOwnershipClient.cancelCreation(settings, "房主", owned));
            assertEquals(cancellations.getFirst(), cancellations.getLast());
            assertEquals(sessionId, cancellations.getFirst().get("sessionId").getAsString());
        } finally {
            server.stop(0);
        }
    }

    /** 响应体超限即取消请求，不等待无界内容或解析成空世界。 */
    @Test
    void oversizedResponseIsRejected() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/worlds/acquire", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 70000);
                exchange.getResponseBody().write(new byte[70000]);
            } catch (IOException ignored) { /* 客户端提前取消。 */ }
        });
        server.start();
        try {
            assertThrows(IOException.class, () -> WorldOwnershipClient.acquire(settings(server), "房主", acquiring()));
        } finally {
            server.stop(0);
        }
    }

    /** 收到响应头之后停住的正文同样受十秒总时限保护，不会卡住全部心跳／取消操作。 */
    @Test
    void responseBodyStallTimesOut() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext("/api/v1/worlds/acquire", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                // 清理信号和超时都只是结束这个测试用的停滞响应，无需区分。
                //noinspection ResultOfMethodCallIgnored
                finish.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            assertThrows(IOException.class, () -> WorldOwnershipClient.acquire(settings(server), "房主", acquiring()));
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }

}
