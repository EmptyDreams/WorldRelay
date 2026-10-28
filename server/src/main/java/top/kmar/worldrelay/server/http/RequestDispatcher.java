package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** 统一处理请求的并发准入、请求 ID、异常、日志和资源清理。 */
final class RequestDispatcher implements HttpHandler {

    private final HttpHandler handler;
    private final Semaphore permits;
    private final System.Logger logger = System.getLogger(RequestDispatcher.class.getName());

    RequestDispatcher(HttpHandler handler, int maxConcurrentRequests) {
        this.handler = handler;
        this.permits = new Semaphore(maxConcurrentRequests);
    }

    @Override
    public void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set(HttpResponses.REQUEST_ID_HEADER, requestId);
        boolean admitted = permits.tryAcquire();
        try {
            if (!admitted) {
                HttpResponses.error(exchange, 503, "SERVER_BUSY", "Too many concurrent requests");
            } else {
                handler.handle(exchange);
                if (exchange.getResponseCode() == -1) {
                    throw new IllegalStateException("HTTP handler returned without sending a response");
                }
            }
        } catch (Exception failure) {
            logger.log(System.Logger.Level.WARNING, "HTTP request failed; requestId=" + requestId, failure);
            if (exchange.getResponseCode() == -1) {
                try {
                    HttpResponses.error(exchange, 500, "INTERNAL_ERROR", "Internal server error");
                } catch (IOException sendFailure) {
                    logger.log(System.Logger.Level.DEBUG, "Unable to send error; requestId=" + requestId, sendFailure);
                }
            }
        } finally {
            try {
                exchange.close();
            } finally {
                if (admitted) {
                    permits.release();
                }
                logger.log(System.Logger.Level.INFO, "{0} {1} status={2} durationMs={3} requestId={4}",
                    exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getResponseCode(),
                    (System.nanoTime() - started) / 1_000_000, requestId);
            }
        }
    }

}