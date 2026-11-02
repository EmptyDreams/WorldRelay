package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;

/** 各 JSON 接口共用的请求头校验，保持媒体类型的拒绝规则和响应一致。 */
final class HttpRequests {

    private HttpRequests() {
    }

    /** 返回 true 时已拒绝请求并发送 415 响应，调用方应停止处理请求体。 */
    static boolean rejectNonJsonContentType(HttpExchange exchange) throws IOException {
        List<String> types = exchange.getRequestHeaders().get("Content-Type");
        if (types == null || types.size() != 1
            || !types.getFirst().split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            HttpResponses.error(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "Expected application/json in UTF-8");
            return true;
        }
        return false;
    }

}
