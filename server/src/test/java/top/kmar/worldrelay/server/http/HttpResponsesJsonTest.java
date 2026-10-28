package top.kmar.worldrelay.server.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.kmar.worldrelay.server.ServerConfig;
import top.kmar.worldrelay.server.json.JsonReader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HttpResponsesJsonTest {

    /** 验证真实 HTTP 错误响应保持状态码、字段顺序和请求 ID，并正确转义及读回 Unicode 和特殊字符。 */
    @Test
    void preservesErrorEnvelopeAndUsesSharedJsonEscaping() throws Exception {
        HttpRouter router = new HttpRouter();
        // 处理器仅用于验证响应工具，不在生产入口注册。
        router.register("GET", "/test-error", exchange ->
            HttpResponses.error(exchange, 400, "BAD_\"\\", "错误🌍\n\t" + (char) 1));
        try (WorldRelayHttpServer server = new WorldRelayHttpServer(
            new ServerConfig("127.0.0.1", 0, 32, 0), router);
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.address().getPort();
            for (String path : new String[]{"/missing", "/test-error"}) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String requestId = response.headers().firstValue(HttpResponses.REQUEST_ID_HEADER).orElseThrow();
                assertEquals(requestId, UUID.fromString(requestId).toString());
                assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
                if (path.equals("/missing")) {
                    assertEquals(404, response.statusCode());
                    assertEquals("{\"code\":\"NOT_FOUND\",\"message\":\"Route not found\",\"requestId\":\""
                        + requestId + "\"}", response.body());
                } else {
                    assertEquals(400, response.statusCode());
                    assertEquals("{\"code\":\"BAD_\\\"\\\\\",\"message\":\"错误🌍\\n\\t\\u0001\",\"requestId\":\""
                        + requestId + "\"}", response.body());
                }

                JsonReader reader = new JsonReader(response.body());
                reader.beginObject();
                assertEquals("code", reader.nextName());
                assertEquals(path.equals("/missing") ? "NOT_FOUND" : "BAD_\"\\", reader.nextString());
                assertEquals("message", reader.nextName());
                assertEquals(path.equals("/missing") ? "Route not found" : "错误🌍\n\t" + (char) 1, reader.nextString());
                assertEquals("requestId", reader.nextName());
                assertEquals(requestId, reader.nextString());
                reader.endObject();
                reader.finish();
            }
        }
    }
}
