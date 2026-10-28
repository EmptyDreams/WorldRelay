package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpServer;
import top.kmar.worldrelay.server.ServerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 管理监听器及请求执行器；每个实例只允许启动一次。 */
public final class WorldRelayHttpServer implements AutoCloseable {

    private final ServerConfig config;
    private final HttpRouter router;
    private HttpServer server;
    private ExecutorService executor;
    private boolean closed;

    public WorldRelayHttpServer(ServerConfig config, HttpRouter router) {
        this.config = Objects.requireNonNull(config, "config");
        this.router = Objects.requireNonNull(router, "router");
    }

    public synchronized void start() throws IOException {
        if (closed || server != null) {
            throw new IllegalStateException("HTTP server cannot be started again");
        }
        HttpServer listener = HttpServer.create();
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        try {
            listener.setExecutor(requests);
            listener.createContext("/", new RequestDispatcher(router, config.maxConcurrentRequests()));
            router.freeze();
            listener.bind(new InetSocketAddress(config.bindAddress(), config.port()), 0);
            listener.start();
            server = listener;
            executor = requests;
        } catch (IOException | RuntimeException | Error failure) {
            closed = true;
            try {
                listener.stop(0);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                requests.shutdownNow();
            }
            throw failure;
        }
    }

    /** 返回实际监听地址；配置端口为 0 时，包含自动分配的端口。 */
    public synchronized InetSocketAddress address() {
        if (server == null || closed) {
            throw new IllegalStateException("HTTP server is not running");
        }
        return server.getAddress();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (server == null) {
            return;
        }
        long started = System.nanoTime();
        long budget = TimeUnit.SECONDS.toNanos(config.shutdownGraceSeconds());
        try {
            server.stop(config.shutdownGraceSeconds());
        } finally {
            executor.shutdown();
            long remaining = Math.max(0, budget - (System.nanoTime() - started));
            try {
                if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

}