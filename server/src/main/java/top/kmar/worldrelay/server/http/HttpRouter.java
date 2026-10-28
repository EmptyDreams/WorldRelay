package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 按请求方法和原始路径精确匹配，区分大小写；服务启动后禁止注册路由。 */
public final class HttpRouter implements HttpHandler {

    private final Map<String, Map<String, HttpHandler>> routes = new HashMap<>();
    private boolean frozen;

    public synchronized void register(String method, String path, HttpHandler handler) {
        if (frozen) {
            throw new IllegalStateException("Routes cannot be registered after server startup");
        }
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        if (!method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        URI uri = URI.create(path);
        if (!path.startsWith("/") || !path.equals(uri.getRawPath())
            || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() != null) {
            throw new IllegalArgumentException("Route must be an absolute path without query or fragment: " + path);
        }
        Map<String, HttpHandler> methods = routes.computeIfAbsent(path, ignored -> new TreeMap<>());
        if (methods.putIfAbsent(method, handler) != null) {
            throw new IllegalArgumentException("Duplicate route: " + method + " " + path);
        }
    }

    synchronized void freeze() {
        frozen = true;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 路由表在 HTTP 服务启动前冻结，处理请求期间不会再被修改。
        Map<String, HttpHandler> methods = routes.get(exchange.getRequestURI().getRawPath());
        if (methods == null) {
            HttpResponses.error(exchange, 404, "NOT_FOUND", "Route not found");
            return;
        }
        HttpHandler handler = methods.get(exchange.getRequestMethod());
        if (handler == null) {
            exchange.getResponseHeaders().set("Allow", String.join(", ", methods.keySet()));
            HttpResponses.error(exchange, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
            return;
        }
        handler.handle(exchange);
    }

}