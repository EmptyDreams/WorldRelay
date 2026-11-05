package top.kmar.worldrelay.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonReader;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class WorldCreationTest {

    @TempDir
    Path directory;

    private ServerConfig config() {
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0,
            directory.resolve("users.json"), directory.resolve("worlds"));
    }

    /**
     * 验证本机无需凭据即可创建世界，重复名称返回 409，业务列表仍需鉴权且两个端口的方法互相隔离；
     * 世界记录和名称约束重启后保留，停服删除一个目录后只移除目标世界，原用户凭据仍可查询列表。
     * 列表认证使用普通注册返回的 API 密钥，覆盖注册接口同时生成 SSH 凭据后的世界管理回归场景。
     */
    @Test
    void createsLocallyRejectsDuplicateNamesAndPersistsUntilFolderDeletion() throws Exception {
        String auth = registerUser();
        String first;
        String second;
        try (HttpClient client = HttpClient.newHttpClient()) {
            try (WorldRelayService service = new WorldRelayService(config())) {
                service.start();
                assertEquals(List.of(), listIds(list(client, service, auth).body()));
                HttpResponse<String> created = client.send(create(service, "{\"name\":\"共同世界🌍\"}"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertEquals(201, created.statusCode());
                assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals("application/json; charset=utf-8", created.headers().firstValue("Content-Type").orElseThrow());
                assertTrue(created.headers().firstValue("X-Request-Id").isPresent());
                first = stringField(created.body(), "worldId");
                assertEquals("{\"worldId\":\"" + first + "\",\"name\":\"共同世界🌍\",\"status\":\"FREE\","
                    + "\"hostName\":null,\"playerCount\":0,\"lastCommittedAt\":null,\"endpoint\":null}", created.body());
                assertTrue(Files.isRegularFile(config().worldsDirectory().resolve(first).resolve("world.json")));
                HttpResponse<String> duplicateName = client.send(create(service, "{\"name\":\"共同世界🌍\"}"),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(409, duplicateName.statusCode());
                assertTrue(duplicateName.body().contains("\"code\":\"WORLD_NAME_EXISTS\""));
                assertTrue(duplicateName.headers().firstValue("X-Request-Id").isPresent());
                assertEquals(List.of(first), listIds(list(client, service, auth).body()));
                HttpResponse<String> anotherWorld = client.send(create(service, "{\"name\":\"另一个世界🌍\"}"),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(201, anotherWorld.statusCode());
                second = stringField(anotherWorld.body(), "worldId");
                assertNotEquals(first, second);
                assertEquals(Stream.of(first, second).sorted().toList(), listIds(list(client, service, auth).body()));

                // 业务端口不能创建世界，本机管理端口也不提供世界列表。
                URI business = uri(service.apiAddress().getPort(), "/api/v1/worlds");
                var post = HttpRequest.newBuilder(business).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"禁止\"}"));
                assertEquals(401, client.send(post.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                var wrongPort = client.send(post.header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(405, wrongPort.statusCode());
                assertEquals("GET", wrongPort.headers().firstValue("Allow").orElseThrow());
                var localGet = client.send(HttpRequest.newBuilder(uri(service.registrationAddress().getPort(),
                    "/api/v1/worlds")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(405, localGet.statusCode());
                assertEquals("POST", localGet.headers().firstValue("Allow").orElseThrow());
            }
            try (WorldRelayService restarted = new WorldRelayService(config())) {
                restarted.start();
                assertEquals(2, listIds(list(client, restarted, auth).body()).size());
                assertEquals(409, client.send(create(restarted, "{\"name\":\"共同世界🌍\"}"),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            // 模拟用户在停服后删除目标目录，再验证重启不需要清理任何目录外索引。
            Files.delete(config().worldsDirectory().resolve(first).resolve("world.json"));
            Files.delete(config().worldsDirectory().resolve(first));
            try (WorldRelayService restarted = new WorldRelayService(config())) {
                restarted.start();
                assertEquals(List.of(second), listIds(list(client, restarted, auth).body()));
            }
        }
    }

    /**
     * 验证非法 JSON、字段、名称、UTF-8 编码、请求大小和媒体类型分别被拒绝且不创建记录；
     * 名称长度按 Unicode 码点计算，64 个表情符号可创建，65 个被拒绝。
     */
    @Test
    void invalidRequestsDoNotCreateRecordsAndUnicodeLimitCountsCodePoints() throws Exception {
        try (WorldRelayService service = new WorldRelayService(config()); HttpClient client = HttpClient.newHttpClient()) {
            service.start();
            for (String body : new String[]{"", "{", "[]", "{}", "{\"name\":null}", "{\"name\":7}",
                "{\"name\":\"\"}", "{\"name\":\" 前\"}", "{\"name\":\"后 \"}", "{\"name\":\"a\\nb\"}",
                "{\"name\":\"a\\u0000b\"}", "{\"name\":\"\\uD800\"}", "{\"name\":\"" + "🌍".repeat(65) + "\"}",
                "{\"wrong\":\"name\"}", "{\"name\":\"a\",\"extra\":true}", "{\"name\":\"a\",\"worldId\":\"given\"}",
                "{\"name\":\"a\",\"name\":\"b\"}",
                "{\"name\":\"a\"} {}"}) {
                var rejected = client.send(create(service, body), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, rejected.statusCode(), body);
                assertTrue(rejected.body().contains("INVALID_WORLD"));
            }
            URI local = uri(service.registrationAddress().getPort(), "/api/v1/worlds");
            var invalidUtf8 = HttpRequest.newBuilder(local).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xff})).build();
            assertEquals(400, client.send(invalidUtf8, HttpResponse.BodyHandlers.ofString()).statusCode());
            var tooLarge = client.send(create(service, " ".repeat(4097)), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, tooLarge.statusCode());
            for (String type : new String[]{"", "text/plain"}) {
                var builder = HttpRequest.newBuilder(local).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"a\"}"));
                if (!type.isEmpty()) {
                    builder.header("Content-Type", type);
                }
                assertEquals(415, client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            try (var files = Files.list(config().worldsDirectory())) {
                assertEquals(1, files.count()); // 无效请求只留下服务级锁文件。
            }
            assertEquals(201, client.send(create(service, "{\"name\":\"" + "🌍".repeat(64) + "\"}"),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    /** 为世界列表回归测试创建非覆盖注册用户，只提取返回的 API 密钥构造 Basic 凭据。 */
    private String registerUser() throws Exception {
        try (UserStore users = UserStore.open(config().usersFile())) {
            var registration = users.register("玩家", false);
            assertNotNull(registration, "测试前置条件：世界列表查询用户应注册成功");
            String key = registration.token();
            return "Basic " + Base64.getEncoder().encodeToString(("玩家:" + key).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static HttpRequest create(WorldRelayService service, String body) {
        return HttpRequest.newBuilder(uri(service.registrationAddress().getPort(), "/api/v1/worlds"))
            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private static HttpResponse<String> list(HttpClient client, WorldRelayService service, String auth) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri(service.apiAddress().getPort(), "/api/v1/worlds"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"mock\":false"));
        return response;
    }

    @SuppressWarnings("SameParameterValue")
    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @SuppressWarnings("SameParameterValue")
    private static String stringField(String json, String field) {
        JsonReader reader = new JsonReader(json);
        reader.beginObject();
        String result = null;
        while (reader.hasNext()) {
            if (reader.nextName().equals(field)) {
                result = reader.nextString();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        reader.finish();
        assertNotNull(result);
        return result;
    }

    private static List<String> listIds(String json) {
        JsonReader reader = new JsonReader(json);
        List<String> ids = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            if (!reader.nextName().equals("worlds")) {
                reader.skipValue();
                continue;
            }
            reader.beginArray();
            while (reader.hasNext()) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if (reader.nextName().equals("worldId")) {
                        ids.add(reader.nextString());
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
            reader.endArray();
        }
        reader.endObject();
        reader.finish();
        return ids;
    }

}
