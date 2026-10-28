package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** 轻量响应辅助方法；请求调度器负责管理并关闭 exchange。 */
public final class HttpResponses {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private HttpResponses() {
    }

    public static void text(HttpExchange exchange, int status, String body) throws IOException {
        bytes(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    public static void bytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        Objects.requireNonNull(body, "body");
        validateStatus(status);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (status == 204 || status == 205 || status == 304) {
            noContent(exchange, status);
        } else if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            noContent(exchange, status);
        } else if (body.length == 0) {
            noContent(exchange, status);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    public static void noContent(HttpExchange exchange, int status) throws IOException {
        validateStatus(status);
        exchange.sendResponseHeaders(status, -1);
    }

    public static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        String requestId = exchange.getResponseHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            exchange.getResponseHeaders().set(REQUEST_ID_HEADER, requestId);
        }
        String body = new JsonWriter().beginObject()
            .name("code").value(code)
            .name("message").value(message)
            .name("requestId").value(requestId)
            .endObject().finish();
        bytes(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateStatus(int status) {
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("Expected a final HTTP status between 200 and 599");
        }
    }

}