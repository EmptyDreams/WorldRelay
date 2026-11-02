package top.kmar.worldrelay.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.http.BasicAuthenticationHandler;
import top.kmar.worldrelay.server.http.HttpResponses;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.world.WorldStore;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class WorldRelayServiceTest {

    @TempDir
    Path directory;

    private ServerConfig config() {
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0, directory.resolve("users.json"), directory.resolve("worlds"));
    }

    /**
     * 验证中文用户名注册返回 API 密钥、Ed25519 公钥和 OpenSSH 私钥，重复注册返回冲突。
     * API 密钥可用于 Basic 鉴权且重启后仍然有效；原密钥和 SSH 私钥不进入持久化文件或请求日志。
     */
    @Test
    void registersAuthenticatesAndRetainsCredentialsAcrossRestartWithoutLoggingKeys() throws Exception {
        List<LogRecord> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger("top.kmar.worldrelay.server.http.RequestDispatcher");
        logger.addHandler(capture);
        String key, privateKey;
        try (HttpClient client = client()) {
            try (WorldRelayService service = new WorldRelayService(config())) {
                service.start();
                assertEquals("127.0.0.1", service.registrationAddress().getAddress().getHostAddress());
                assertNotEquals(service.apiAddress().getPort(), service.registrationAddress().getPort());
                HttpResponse<String> registered = send(client, register(service, "{\"username\":\"小明🌍\"}"));
                assertEquals(201, registered.statusCode());
                assertEquals("no-store", registered.headers().firstValue("Cache-Control").orElseThrow());
                assertTrue(registered.headers().firstValue(HttpResponses.REQUEST_ID_HEADER).isPresent());
                JsonReader reader = new JsonReader(registered.body());
                reader.beginObject();
                assertEquals("username", reader.nextName());
                assertEquals("小明🌍", reader.nextString());
                assertEquals("key", reader.nextName());
                key = reader.nextString();
                assertEquals("publicKey", reader.nextName());
                assertTrue(reader.nextString().startsWith("ssh-ed25519 "));
                assertEquals("privateKey", reader.nextName());
                privateKey = reader.nextString();
                assertTrue(privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"));
                reader.endObject();
                reader.finish();
                assertTrue(key.matches("[A-Za-z0-9_-]{43}"));
                assertEquals(409, send(client, register(service, "{\"username\":\"小明🌍\"}")).statusCode());
                HttpResponse<String> worlds = send(client, api(service, "/api/v1/worlds")
                    .header("Authorization", basic("小明🌍", key)).GET().build());
                assertEquals(200, worlds.statusCode());
                assertEquals("{\"mock\":false,\"worlds\":[]}", worlds.body());
            }
            try (WorldRelayService restarted = new WorldRelayService(config())) {
                restarted.start();
                assertEquals(200, send(client, api(restarted, "/api/v1/worlds")
                    .header("Authorization", basic("小明🌍", key)).GET().build()).statusCode());
            }
            assertFalse(Files.readString(config().usersFile()).contains(key));
            String logged = logs.stream().map(record -> record.getMessage() + java.util.Arrays.toString(record.getParameters())
                + record.getThrown()).reduce("", String::concat);
            assertFalse(logged.contains(key));
            assertFalse(logged.contains(basic("小明🌍", key)));
            assertFalse(logged.contains(privateKey));
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertFalse(Files.readString(file).contains(privateKey));
                }
            }
        } finally {
            logger.removeHandler(capture);
        }
    }

    /** 验证请求未携带认证凭据时返回 401。 */
    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        try (WorldRelayService service = new WorldRelayService(config()); HttpClient client = client()) {
            service.start();
            HttpResponse<String> response = send(client, api(service, "/api/v1/worlds").GET().build());
            assertEquals(401, response.statusCode());
        }
    }

    /**
     * 使用普通注册返回的 API 密钥验证 ping 的生产鉴权链：有效凭据返回空的 204，无效凭据统一返回 401。
     * 同时验证响应禁止缓存、携带请求 ID，错误方法返回 405，注册端口不提供此业务接口。
     */
    @Test
    void pingRequiresAuthenticationAndReturnsNoContent() throws Exception {
        String key;
        try (UserStore users = UserStore.open(config().usersFile())) {
            var registration = users.register("ping-player", false);
            assertNotNull(registration, "测试前置条件：ping 测试用户应注册成功");
            key = registration.key();
        }
        try (WorldRelayService service = new WorldRelayService(config()); HttpClient client = client()) {
            service.start();
            HttpResponse<String> ping = send(client, api(service, "/api/v1/ping")
                .header("Authorization", basic("ping-player", key)).GET().build());
            assertEquals(204, ping.statusCode());
            assertEquals("", ping.body());
            assertEquals("no-store", ping.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(ping.headers().firstValue(HttpResponses.REQUEST_ID_HEADER).isPresent());

            for (String authorization : List.of("", "Basic invalid", basic("unknown-player", key),
                basic("ping-player", "x".repeat(43)))) {
                HttpRequest.Builder request = api(service, "/api/v1/ping");
                if (!authorization.isEmpty()) {
                    request.header("Authorization", authorization);
                }
                HttpResponse<String> rejected = send(client, request.GET().build());
                assertEquals(401, rejected.statusCode());
                assertEquals(BasicAuthenticationHandler.CHALLENGE,
                    rejected.headers().firstValue("WWW-Authenticate").orElseThrow());
                assertTrue(rejected.body().contains("\"code\":\"UNAUTHORIZED\""));
            }

            HttpResponse<String> wrongMethod = send(client, api(service, "/api/v1/ping")
                .header("Authorization", basic("ping-player", key)).POST(HttpRequest.BodyPublishers.noBody()).build());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
            HttpResponse<String> registrationPort = send(client, HttpRequest.newBuilder(
                uri(service.registrationAddress().getPort(), "/api/v1/ping")).timeout(Duration.ofSeconds(5)).GET().build());
            assertEquals(404, registrationPort.statusCode());
        }
    }

    /**
     * 验证 12 个同名普通注册请求并发执行时仅一个返回 201，其余 11 个返回 409，避免重复创建用户。
     * 关闭并重新打开用户存储后，同名非覆盖注册仍被拒绝，确认成功的注册已经持久化。
     */
    @Test
    void concurrentDuplicateRegistrationCreatesExactlyOneUser() throws Exception {
        try (WorldRelayService service = new WorldRelayService(config()); HttpClient client = client()) {
            service.start();
            List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                requests.add(client.sendAsync(register(service, "{\"username\":\"race\"}"), HttpResponse.BodyHandlers.ofString()));
            }
            List<Integer> statuses = requests.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).toList();
            assertEquals(1, statuses.stream().filter(status -> status == 201).count());
            assertEquals(11, statuses.stream().filter(status -> status == 409).count());
        }
        try (UserStore users = UserStore.open(config().usersFile())) {
            assertNull(users.register("race", false));
        }
    }

    /**
     * 占用业务端口模拟启动失败，验证本机管理监听器被关闭，失败的服务实例不能再次启动或提供地址。
     * 随后重新绑定管理端口、打开用户和世界存储并注册新用户，确认端口及用户、SSH 公钥、世界存储锁均已释放。
     */
    @Test
    void startupFailureClosesRegistrationListenerAndReleasesStoreLock() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int registrationPort;
        try (ServerSocket available = new ServerSocket(0, 0, loopback)) {
            registrationPort = available.getLocalPort();
        }
        try (ServerSocket occupiedApi = new ServerSocket(0, 0, loopback)) {
            ServerConfig config = new ServerConfig("127.0.0.1", occupiedApi.getLocalPort(), 32, 0,
                registrationPort, directory.resolve("users.json"), config().worldsDirectory());
            try (WorldRelayService service = new WorldRelayService(config)) {
                assertThrows(java.io.IOException.class, service::start);
                assertThrows(IllegalStateException.class, service::apiAddress);
                assertThrows(IllegalStateException.class, service::start);
            }
        }
        try (
            ServerSocket ignored = new ServerSocket(registrationPort, 0, loopback);
            UserStore users = UserStore.open(config().usersFile());
            WorldStore worlds = WorldStore.open(config().worldsDirectory())
        ) {
            assertNotNull(users.register("after-failure", false));
            assertTrue(worlds.list().isEmpty());
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** 验证 HTTP 覆盖参数重置接口 Token 和 SSH 私钥；布尔参数可位于用户名之前，旧 Token 立即失效。 */
    @Test
    void overwritesUserOnlyWithExplicitBooleanFlag() throws Exception {
        try (var service = new WorldRelayService(config()); var client = client()) {
            service.start();
            var original = send(client, register(service, "{\"username\":\"Dev\"}"));
            assertEquals(201, original.statusCode());
            String oldKey = field(original.body(), "key");
            String oldPrivateKey = field(original.body(), "privateKey");
            assertEquals(409, send(client, register(service, "{\"username\":\"Dev\",\"overwrite\":false}")).statusCode());
            var reset = send(client, register(service, "{\"overwrite\":true,\"username\":\"Dev\"}"));
            assertEquals(200, reset.statusCode());
            assertEquals("no-store", reset.headers().firstValue("Cache-Control").orElseThrow());
            String newKey = field(reset.body(), "key");
            assertNotEquals(oldKey, newKey);
            assertNotEquals(oldPrivateKey, field(reset.body(), "privateKey"));
            assertEquals(401, send(client, api(service, "/api/v1/ping").header("Authorization", basic("Dev", oldKey)).GET().build()).statusCode());
            assertEquals(204, send(client, api(service, "/api/v1/ping").header("Authorization", basic("Dev", newKey)).GET().build()).statusCode());
        }
    }

    /** 验证注册接口保护同名普通 SSH 行，且严格拒绝字符串、数字、空值或重复字段形式的覆盖参数。 */
    @Test
    void rejectsUnmanagedSshCollisionAndMalformedOverwriteFlags() throws Exception {
        Path keys = config().ssh().authorizedKeysFile();
        Files.createDirectories(keys.getParent());
        Files.writeString(keys, "ssh-ed25519 AAAA Dev\n");
        try (var service = new WorldRelayService(config()); var client = client()) {
            service.start();
            var conflict = send(client, register(service, "{\"username\":\"Dev\",\"overwrite\":true}"));
            assertEquals(409, conflict.statusCode());
            assertTrue(conflict.body().contains("AUTHORIZED_KEYS_CONFLICT"));
            assertEquals("ssh-ed25519 AAAA Dev\n", Files.readString(keys));
            assertFalse(Files.exists(config().usersFile()));
            for (String body : List.of("{\"username\":\"other\",\"overwrite\":\"true\"}",
                "{\"username\":\"other\",\"overwrite\":1}", "{\"username\":\"other\",\"overwrite\":null}",
                "{\"username\":\"other\",\"overwrite\":true,\"overwrite\":false}",
                "{\"username\":\"other\",\"extra\":true}")) {
                assertEquals(400, send(client, register(service, body)).statusCode());
            }
        }
    }

    private static String field(String json, String name) {
        JsonReader reader = new JsonReader(json);
        reader.beginObject();
        String result = null;
        while (reader.hasNext()) {
            String field = reader.nextName();
            String value = reader.nextString();
            if (field.equals(name)) result = value;
        }
        reader.endObject();
        reader.finish();
        return java.util.Objects.requireNonNull(result);
    }

    private static HttpRequest register(WorldRelayService service, String body) {
        return HttpRequest.newBuilder(uri(service.registrationAddress().getPort(), "/api/v1/register"))
            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    @SuppressWarnings("SameParameterValue")
    private static HttpRequest.Builder api(WorldRelayService service, String path) {
        return HttpRequest.newBuilder(uri(service.apiAddress().getPort(), path)).timeout(Duration.ofSeconds(5));
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @SuppressWarnings("SameParameterValue")
    private static String basic(String username, String key) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

}
