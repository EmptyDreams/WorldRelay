package top.kmar.worldrelay.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.http.BasicAuthenticationHandler;
import top.kmar.worldrelay.server.http.HttpResponses;
import top.kmar.worldrelay.server.json.JsonReader;

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
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0, directory.resolve("users.json"));
    }

    /** 验证中文用户名注册、重复注册拒绝、Basic 鉴权及重启后凭据有效，且文件和日志不泄露原密钥。 */
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
        String key;
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
                reader.endObject();
                reader.finish();
                assertTrue(key.matches("[A-Za-z0-9_-]{43}"));
                assertEquals(409, send(client, register(service, "{\"username\":\"小明🌍\"}")).statusCode());
                HttpResponse<String> worlds = send(client, api(service, "/api/v1/worlds")
                    .header("Authorization", basic("小明🌍", key)).GET().build());
                assertEquals(200, worlds.statusCode());
                assertTrue(worlds.body().contains("\"mock\":true"));
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

    /** 验证 ping 经过生产鉴权链，成功时仅返回空响应，且不在注册端口提供。 */
    @Test
    void pingRequiresAuthenticationAndReturnsNoContent() throws Exception {
        String key;
        try (UserStore users = UserStore.open(config().usersFile())) {
            key = users.register("ping-player");
            assertNotNull(key);
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

    /** 验证同一用户名并发注册时恰好一次成功，其余返回冲突，且结果已持久化。 */
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
            assertNull(users.register("race"));
        }
    }

    /** 验证业务端口被占用导致启动失败时关闭已启动的注册监听器、释放文件锁，并禁止重启该实例。 */
    @Test
    void startupFailureClosesRegistrationListenerAndReleasesStoreLock() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int registrationPort;
        try (ServerSocket available = new ServerSocket(0, 0, loopback)) {
            registrationPort = available.getLocalPort();
        }
        try (ServerSocket occupiedApi = new ServerSocket(0, 0, loopback)) {
            ServerConfig config = new ServerConfig("127.0.0.1", occupiedApi.getLocalPort(), 32, 0,
                registrationPort, directory.resolve("users.json"));
            try (WorldRelayService service = new WorldRelayService(config)) {
                assertThrows(java.io.IOException.class, service::start);
                assertThrows(IllegalStateException.class, service::apiAddress);
                assertThrows(IllegalStateException.class, service::start);
            }
        }
        try (
            ServerSocket ignored = new ServerSocket(registrationPort, 0, loopback);
            UserStore users = UserStore.open(config().usersFile())
        ) {
            assertNotNull(users.register("after-failure"));
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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