package top.kmar.worldrelay.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.world.WorldStore;
import top.kmar.worldrelay.tunnel.TunnelConnector;
import top.kmar.worldrelay.tunnel.TunnelEndpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class WorldOwnershipHttpTest {

    @TempDir
    Path directory;
    private String world, secondWorld, auth, guestAuth;
    private final String device = id();
    private final String request = id();

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private ServerConfig config() {
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0, directory.resolve("users.json"), directory.resolve("worlds"), 5, 20);
    }

    /**
     * 为每个所有权 HTTP 用例分别普通注册房主和访客，从注册结果提取 API 密钥，供后续请求验证真实用户身份及越权拒绝。
     * 同时创建两个独立空闲世界，确保不同用例及并发获取场景不会复用已经持有的世界。
     */
    @BeforeEach
    void prepare() throws Exception {
        try (UserStore users = UserStore.open(config().usersFile())) {
            var host = users.register("房主", false);
            var guest = users.register("访客", false);
            assertNotNull(host, "测试前置条件：房主应注册成功");
            assertNotNull(guest, "测试前置条件：访客应注册成功");
            auth = basic("房主", host.token());
            guestAuth = basic("访客", guest.token());
        }
        try (WorldStore worlds = WorldStore.open(config().worldsDirectory())) {
            var first = worlds.create("联机世界");
            assertNotNull(first, "测试前置条件：联机世界应创建成功");
            world = first.worldId();
            var second = worlds.create("并发重试世界");
            assertNotNull(second, "测试前置条件：并发重试世界应创建成功");
            secondWorld = second.worldId();
        }
    }

    private static String basic(String user, String key) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpRequest post(WorldRelayService service, String action, String fields, String authorization) {
        return HttpRequest.newBuilder(uri(service.apiAddress().getPort(), "/api/v1/worlds/" + action))
            .timeout(Duration.ofSeconds(10)).header("Authorization", authorization).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{" + fields + "}", StandardCharsets.UTF_8)).build();
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String acquireFields(String worldId, String deviceId, String requestId) {
        return "\"worldId\":\"" + worldId + "\",\"deviceId\":\"" + deviceId + "\",\"requestId\":\"" + requestId + "\"";
    }

    private String identity(String worldId, String deviceId, String session) {
        return "\"worldId\":\"" + worldId + "\",\"deviceId\":\"" + deviceId
            + "\",\"sessionId\":\"" + session + "\",\"epoch\":1";
    }

    private static String field(String json, String name) {
        JsonReader reader = new JsonReader(json);
        reader.beginObject();
        while (reader.hasNext()) {
            if (reader.nextName().equals(name)) return reader.nextString();
            reader.skipValue();
        }
        throw new AssertionError("Missing field " + name);
    }

    private String list(HttpClient client, WorldRelayService service) throws Exception {
        return send(client, HttpRequest.newBuilder(uri(service.apiAddress().getPort(), "/api/v1/worlds"))
            .header("Authorization", guestAuth).GET().build()).body();
    }

    private static void error(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, field(response.body(), "code"));
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
    }

    /**
     * 通过真实 HTTP 请求验证获取、心跳及两阶段 ready／stop，端点仅在 PORT_OPENED 后公布，BEGIN 后立即撤下。
     * 同时验证获取重试、访客越权和乱序阶段处理；上传完成及重启均保留原会话和所有权，公共列表不泄露会话标识。
     */
    @Test
    void twoStageLifecycleExposesEndpointOnlyAfterReadyAndNeverReleasesOnStop() throws Exception {
        String session;
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config()); var tunnel = new TunnelConnector()) {
            service.start();
            var acquired = send(client, post(service, "acquire", acquireFields(world, device, request), auth));
            assertEquals(201, acquired.statusCode(), acquired.body());
            assertTrue(acquired.body().contains("\"heartbeatIntervalSeconds\":5,\"heartbeatTimeoutSeconds\":20"));
            session = field(acquired.body(), "sessionId");
            String identity = identity(world, device, session);
            var retry = send(client, post(service, "acquire", acquireFields(world, device, request), auth));
            assertEquals(200, retry.statusCode());
            assertEquals(acquired.body(), retry.body());
            assertFalse(list(client, service).contains(session));
            String ticketFields = identity + ",\"role\":\"host\"";
            error(send(client, post(service, "tunnel-ticket", ticketFields, auth)),
                409, "INVALID_PHASE");
            var starting = send(client, post(service, "ready", identity + ",\"stage\":\"SAVE_SYNCED\",\"baseVersion\":null", auth));
            assertEquals("STARTING", field(starting.body(), "phase"));
            assertTrue(starting.body().contains("\"endpoint\":null"));
            var ticket = send(client, post(service, "tunnel-ticket", ticketFields, auth));
            assertEquals(200, ticket.statusCode());
            var endpoint = new TunnelEndpoint(URI.create("ws://127.0.0.1:" + service.tunnelAddress().getPort() + "/api/v1/tunnel"), null);
            var control = tunnel.connect(endpoint, field(ticket.body(), "ticket"), ignored -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
            var running = send(client, post(service, "ready", identity + ",\"stage\":\"PORT_OPENED\",\"tunnelId\":\"" + control.registrationId() + "\"", auth));
            assertEquals("RUNNING", field(running.body(), "status"));
            assertTrue(list(client, service).contains("\"endpoint\":{\"address\":\"" + world + ".localhost\",\"port\":25565}"));
            var heartbeat = send(client, post(service, "heartbeat", identity + ",\"playerCount\":2", auth));
            assertEquals(200, heartbeat.statusCode());
            assertTrue(list(client, service).contains("\"playerCount\":2"));
            error(send(client, post(service, "heartbeat", identity, guestAuth)), 403, "OWNER_MISMATCH");
            error(send(client, post(service, "stop", identity + ",\"stage\":\"UPLOAD_COMPLETE\",\"baseVersion\":null", auth)),
                409, "INVALID_PHASE");
            var stopping = send(client, post(service, "stop", identity + ",\"stage\":\"BEGIN\"", auth));
            assertEquals("STOPPING", field(stopping.body(), "phase"));
            assertFalse(list(client, service).contains("\"address\""));
            var complete = send(client, post(service, "stop", identity + ",\"stage\":\"UPLOAD_COMPLETE\",\"baseVersion\":null", auth));
            assertEquals("COMMITTING", field(complete.body(), "phase"));
            assertTrue(complete.body().contains("\"committedVersion\":null"));
            var lateBegin = send(client, post(service, "stop", identity + ",\"stage\":\"BEGIN\"", auth));
            assertEquals("COMMITTING", field(lateBegin.body(), "phase"));
            error(send(client, post(service, "ready", identity + ",\"stage\":\"SAVE_SYNCED\",\"baseVersion\":null", auth)),
                409, "INVALID_PHASE");
        }
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config())) {
            service.start();
            var retry = send(client, post(service, "acquire", acquireFields(world, device, request), auth));
            assertEquals(200, retry.statusCode());
            assertEquals(session, field(retry.body(), "sessionId"));
            assertEquals("COMMITTING", field(retry.body(), "phase"));
            assertEquals("RECOVERY_REQUIRED", field(retry.body(), "status"));
            error(send(client, post(service, "acquire", acquireFields(world, id(), id()), guestAuth)), 409, "WORLD_OWNED");
            assertEquals("no-store", retry.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    /** 原房主重启后可显式恢复停止中的空世界；请求仍须提供基础版本、通过鉴权且不能中断正在提交的快照。 */
    @Test
    void resumesInterruptedOwnerWithExplicitBaseVersion() throws Exception {
        String identity;
        String session;
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config())) {
            service.start();
            var acquired = send(client, post(service, "acquire", acquireFields(world, device, request), auth));
            session = field(acquired.body(), "sessionId");
            identity = identity(world, device, session);
            error(send(client, post(service, "ready", identity + ",\"stage\":\"RESUME\"", auth)),
                400, "INVALID_OWNERSHIP_REQUEST");
            error(send(client, post(service, "ready", identity + ",\"stage\":\"RESUME\",\"baseVersion\":1", auth)),
                409, "BASE_VERSION_MISMATCH");
            error(send(client, post(service, "ready", identity + ",\"stage\":\"RESUME\",\"baseVersion\":null", guestAuth)),
                403, "OWNER_MISMATCH");
            assertEquals(200, send(client, post(service, "stop", identity + ",\"stage\":\"BEGIN\"", auth)).statusCode());
        }
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config())) {
            service.start();
            var resumed = send(client, post(service, "ready", identity + ",\"stage\":\"RESUME\",\"baseVersion\":null", auth));
            assertEquals(200, resumed.statusCode(), resumed.body());
            assertEquals("STARTING", field(resumed.body(), "phase"));
            assertEquals(session, field(resumed.body(), "sessionId"));
            assertTrue(resumed.body().contains("\"hasSave\":false"));
            assertTrue(resumed.body().contains("\"epoch\":1"));
            assertTrue(resumed.body().contains("\"endpoint\":null"));
            assertEquals(200, send(client, post(service, "stop", identity + ",\"stage\":\"BEGIN\"", auth)).statusCode());
            assertEquals(200, send(client, post(service, "stop", identity + ",\"stage\":\"UPLOAD_COMPLETE\",\"baseVersion\":null", auth)).statusCode());
            error(send(client, post(service, "ready", identity + ",\"stage\":\"RESUME\",\"baseVersion\":null", auth)),
                409, "INVALID_PHASE");
        }
    }

    /**
     * 并发发送不同用户／设备的获取请求，验证只有一个请求成功，其余均收到所有权冲突。
     * 再并发重试同一获取请求，验证仅一次创建会话，其余请求幂等成功且返回同一个 sessionId。
     */
    @Test
    void concurrentAcquisitionHasOneWinnerAndSameRequestRetriesShareOneSession() throws Exception {
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config())) {
            service.start();
            var attempts = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            // 不同请求争抢同一世界，只允许一个获胜者。
            for (int i = 0; i < 20; i++) {
                attempts.add(client.sendAsync(post(service, "acquire",
                    acquireFields(world, id(), id()), i % 2 == 0 ? auth : guestAuth), HttpResponse.BodyHandlers.ofString()));
            }
            var responses = attempts.stream().map(CompletableFuture::join).toList();
            assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
            assertEquals(19, responses.stream().filter(r -> r.statusCode() == 409).count());
            attempts.clear();
            // 同一请求的并发重试应共享结果，不能被当作新的抢锁操作。
            for (int i = 0; i < 20; i++) {
                attempts.add(client.sendAsync(post(service, "acquire",
                    acquireFields(secondWorld, device, request), auth), HttpResponse.BodyHandlers.ofString()));
            }
            responses = attempts.stream().map(CompletableFuture::join).toList();
            assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
            assertEquals(19, responses.stream().filter(r -> r.statusCode() == 200).count());
            assertEquals(1, responses.stream().map(r -> field(r.body(), "sessionId")).distinct().count());
        }
    }

    /**
     * 验证四个业务路由的鉴权、方法限制、管理端口隔离、媒体类型、请求体上限和 UTF-8 校验。
     * 缺失／重复／未知字段、非法 UUID、端点、玩家数量及任期均须返回约定错误，失败请求不能推进世界阶段。
     */
    @Test
    void strictRequestValidationAndRoutesDoNotMutateOwnership() throws Exception {
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config())) {
            service.start();
            for (String action : List.of("acquire", "heartbeat", "ready", "stop", "cancel-creation")) {
                URI uri = uri(service.apiAddress().getPort(), "/api/v1/worlds/" + action);
                error(send(client, HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}")).build()), 401, "UNAUTHORIZED");
                var wrongMethod = send(client, HttpRequest.newBuilder(uri).header("Authorization", auth).GET().build());
                error(wrongMethod, 405, "METHOD_NOT_ALLOWED");
                assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
                error(send(client, HttpRequest.newBuilder(uri(service.registrationAddress().getPort(), "/api/v1/worlds/" + action))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()), 404, "NOT_FOUND");
                error(send(client, HttpRequest.newBuilder(uri).header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()), 415, "UNSUPPORTED_MEDIA_TYPE");
                error(send(client, post(service, action, " ".repeat(4097), auth)), 413, "PAYLOAD_TOO_LARGE");
                error(send(client, post(service, action, "", auth)), 400, "INVALID_OWNERSHIP_REQUEST");
                error(send(client, HttpRequest.newBuilder(uri).header("Authorization", auth).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xff})).build()), 400, "INVALID_OWNERSHIP_REQUEST");
            }
            String valid = acquireFields(world, device, request);
            for (String fields : List.of(valid + ",\"username\":\"访客\"", valid + ",\"worldId\":\"" + world + "\"",
                "\"worldId\":\"../world\",\"deviceId\":\"" + device + "\",\"requestId\":\"" + request + "\"")) {
                error(send(client, post(service, "acquire", fields, auth)), 400, "INVALID_OWNERSHIP_REQUEST");
            }
            error(send(client, post(service, "acquire", acquireFields(id(), device, request), auth)), 404, "WORLD_NOT_FOUND");
            var acquired = send(client, post(service, "acquire", valid, auth));
            assertEquals(201, acquired.statusCode());
            String identity = identity(world, device, field(acquired.body(), "sessionId"));
            for (String fields : List.of(identity + ",\"stage\":\"SAVE_SYNCED\"", identity + ",\"stage\":\"UNKNOWN\"",
                identity + ",\"stage\":\"PORT_OPENED\",\"address\":\"https://host/path\",\"port\":25565",
                identity + ",\"stage\":\"PORT_OPENED\",\"address\":\"host\",\"port\":0")) {
                error(send(client, post(service, "ready", fields, auth)), 400, "INVALID_OWNERSHIP_REQUEST");
            }
            error(send(client, post(service, "heartbeat", identity + ",\"playerCount\":-1", auth)), 400, "INVALID_OWNERSHIP_REQUEST");
            error(send(client, post(service, "heartbeat", identity.replace("\"epoch\":1", "\"epoch\":0"), auth)), 400, "INVALID_OWNERSHIP_REQUEST");
            error(send(client, post(service, "heartbeat", identity.replace("\"epoch\":1", "\"epoch\":2"), auth)), 409, "STALE_EPOCH");
            error(send(client, post(service, "stop", identity + ",\"stage\":\"UPLOAD_COMPLETE\"", auth)), 400, "INVALID_OWNERSHIP_REQUEST");
            assertEquals("PREPARING", field(send(client, post(service, "acquire", valid, auth)).body(), "phase"));
        }
    }

    /** 真实鉴权路由返回明确存档状态；直接取消首建无需额外确认字段。 */
    @Test
    void firstCreationCanBeCancelledOverHttpAndStartedCreationCannot() throws Exception {
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config())) {
            service.start();
            var acquired = send(client, post(service, "acquire", acquireFields(world, device, request), auth));
            assertTrue(acquired.body().contains("\"hasSave\":false"));
            String identity = identity(world, device, field(acquired.body(), "sessionId"));
            error(send(client, post(service, "cancel-creation", identity, guestAuth)), 403, "OWNER_MISMATCH");
            var cancelled = send(client, post(service, "cancel-creation", identity, auth));
            assertEquals(204, cancelled.statusCode());
            assertTrue(cancelled.body().isEmpty());
            assertEquals(204, send(client, post(service, "cancel-creation", identity, auth)).statusCode());
            error(send(client, post(service, "acquire", acquireFields(world, device, request), auth)), 409, "ACQUISITION_CANCELLED");
            var next = send(client, post(service, "acquire", acquireFields(world, device, id()), auth));
            assertTrue(next.body().contains("\"epoch\":3"));
            String nextIdentity = identity(world, device, field(next.body(), "sessionId")).replace("\"epoch\":1", "\"epoch\":3");
            assertEquals(200, send(client, post(service, "ready", nextIdentity + ",\"stage\":\"SAVE_SYNCED\",\"baseVersion\":null", auth)).statusCode());
            error(send(client, post(service, "cancel-creation", nextIdentity, auth)), 409, "INVALID_PHASE");
            assertEquals(204, send(client, post(service, "cancel-creation", identity, auth)).statusCode());
            assertTrue(list(client, service).contains("STARTING"));
        }
    }

}
