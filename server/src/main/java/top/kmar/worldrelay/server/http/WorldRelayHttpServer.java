package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import top.kmar.worldrelay.server.ServerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/** 管理监听器及请求执行器；每个实例只允许启动一次。 */
public final class WorldRelayHttpServer implements AutoCloseable {

    private final InetSocketAddress bindAddress;
    private final int maxConcurrentRequests;
    private final int shutdownGraceSeconds;
    private final HttpRouter router;
    private final UnaryOperator<HttpHandler> middleware;
    private HttpServer server;
    private ExecutorService executor;
    private boolean closed;

    public WorldRelayHttpServer(ServerConfig config, HttpRouter router) {
        this(new InetSocketAddress(config.bindAddress(), config.port()), config.maxConcurrentRequests(),
            config.shutdownGraceSeconds(), router, UnaryOperator.identity());
    }

    /** middleware 位于请求 ID / 异常处理之内、路由之前，覆盖所有路径及方法。 */
    public WorldRelayHttpServer(
        InetSocketAddress bindAddress, int maxConcurrentRequests, int shutdownGraceSeconds,
        HttpRouter router, UnaryOperator<HttpHandler> middleware
    ) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (maxConcurrentRequests < 1 || shutdownGraceSeconds < 0) {
            throw new IllegalArgumentException("Invalid HTTP concurrency or shutdown grace");
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.shutdownGraceSeconds = shutdownGraceSeconds;
        this.router = Objects.requireNonNull(router, "router");
        this.middleware = Objects.requireNonNull(middleware, "middleware");
    }

    public synchronized void start() throws IOException {
        if (closed || server != null) {
            throw new IllegalStateException("HTTP server cannot be started again");
        }
        HttpServer listener = HttpServer.create();
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        try {
            listener.setExecutor(requests);
            listener.createContext("/", new RequestDispatcher(middleware.apply(router), maxConcurrentRequests));
            router.freeze();
            listener.bind(bindAddress, 0);
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
        long budget = TimeUnit.SECONDS.toNanos(shutdownGraceSeconds);
        try {
            server.stop(shutdownGraceSeconds);
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