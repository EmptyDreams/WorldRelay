package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;

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
        String body = "{\"code\":" + quote(code) + ",\"message\":" + quote(message)
            + ",\"requestId\":" + quote(requestId) + "}";
        bytes(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateStatus(int status) {
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("Expected a final HTTP status between 200 and 599");
        }
    }

    // 当前仅为统一错误响应输出 JSON；DTO 解析和通用编解码工具留待后续实现。
    private static String quote(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u00")
                            .append(Character.forDigit(character >> 4, 16))
                            .append(Character.forDigit(character & 15, 16));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

}