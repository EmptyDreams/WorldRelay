package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import top.kmar.mc.worldreplay.sync.RsyncMode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TunnelApiTest {

    /** 服务端选择 WSS 或 TCP 后，客户端派生入口并传递玩家确认结果，不要求另填公网地址。 */
    @Test
    void followsAdvertisedTransportAndScopesNewPlayerConsent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicReference<JsonObject> request = new AtomicReference<>();
        server.createContext("/api/v1/worlds/tunnel-ticket", exchange -> {
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var settings = new ClientConnectionSettings("http://127.0.0.1", server.getAddress().getPort(), "a".repeat(43), RsyncMode.AUTO, "", "");
            String common = "\"ticket\":\"" + "b".repeat(43) + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(60) + "\"";
            reply.set("{" + common + ",\"mode\":\"wss\",\"path\":\"/api/v1/tunnel\"}");
            String world = UUID.randomUUID().toString();
            var wss = TunnelApi.ticket(settings, "Guest", world, null, "c".repeat(64), false);
            assertEquals("ws", wss.endpoint().uri().getScheme());
            assertEquals(server.getAddress().getPort(), wss.endpoint().port());
            assertFalse(request.get().get("allowNewPlayer").getAsBoolean());
            reply.set("{" + common + ",\"mode\":\"tcp\",\"host\":\"relay.example.com\",\"port\":2222,\"fingerprint\":\"" + "d".repeat(64) + "\"}");
            var tcp = TunnelApi.ticket(settings, "Guest", world, null, "c".repeat(64), true);
            assertEquals("tls://relay.example.com:2222", tcp.endpoint().uri().toString());
            assertEquals("d".repeat(64), tcp.endpoint().fingerprint());
            assertTrue(request.get().get("allowNewPlayer").getAsBoolean());
            assertEquals(world, request.get().get("worldId").getAsString());
            assertFalse(tcp.toString().contains("b".repeat(43)));
        } finally {
            server.stop(0);
        }
    }

    /** 心跳恢复但入口尚未重新确认时通知房主重新发布，恢复 RUNNING 后不重复登记。 */
    @Test
    void detectsNeedToRepublishAfterHeartbeatRecovery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String world = UUID.randomUUID().toString(), device = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
        var owner = new LocalWorldSession(world, device, UUID.randomUUID().toString(), session, 1, LocalWorldSession.Stage.STARTED, "world");
        AtomicReference<String> status = new AtomicReference<>("RECOVERY_REQUIRED");
        server.createContext("/api/v1/worlds/heartbeat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = "{\"worldId\":\"" + world + "\",\"sessionId\":\"" + session + "\",\"epoch\":1,\"phase\":\"RUNNING\",\"status\":\"" + status.get()
                + "\",\"hasSave\":false,\"baseVersion\":null,\"committedVersion\":null,\"heartbeatIntervalSeconds\":10,\"heartbeatTimeoutSeconds\":30}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var settings = new ClientConnectionSettings("http://127.0.0.1", server.getAddress().getPort(), "a".repeat(43), RsyncMode.AUTO, "", "");
            assertTrue(WorldOwnershipClient.heartbeat(settings, "Host", owner, 1));
            status.set("RUNNING");
            assertFalse(WorldOwnershipClient.heartbeat(settings, "Host", owner, 1));
        } finally {
            server.stop(0);
        }
    }

}
