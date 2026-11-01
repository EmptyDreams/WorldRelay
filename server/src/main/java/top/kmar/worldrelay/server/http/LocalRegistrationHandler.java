package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

/** 只信任真实 socket 地址；不使用 Host、Forwarded 或 X-Forwarded-For 判断来源。 */
public final class LocalRegistrationHandler implements HttpHandler {

    private final HttpHandler next;

    public LocalRegistrationHandler(HttpHandler next) {
        this.next = Objects.requireNonNull(next, "next");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (isNotLocal(exchange.getLocalAddress()) || isNotLocal(exchange.getRemoteAddress())) {
            HttpResponses.error(exchange, 403, "LOCAL_ACCESS_REQUIRED", "Local management requires 127.0.0.1");
            return;
        }
        next.handle(exchange);
    }

    private static boolean isNotLocal(InetSocketAddress address) {
        return address == null || address.getAddress() == null
            || !"127.0.0.1".equals(address.getAddress().getHostAddress());
    }

}
