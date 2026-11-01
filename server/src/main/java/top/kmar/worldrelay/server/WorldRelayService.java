package top.kmar.worldrelay.server;

import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.http.*;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

/** 统一管理双监听器与用户、世界存储锁；启动失败时整体回滚。 */
public final class WorldRelayService implements AutoCloseable {

    private final ServerConfig config;
    private UserStore users;
    private WorldStore worlds;
    private WorldRelayHttpServer api;
    private WorldRelayHttpServer registration;
    private boolean closed;

    public WorldRelayService(ServerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized void start() throws IOException {
        if (closed || users != null) {
            throw new IllegalStateException("Service cannot be started again");
        }
        try {
            users = UserStore.open(config.usersFile());
            worlds = WorldStore.open(config.worldsDirectory(), config.heartbeatIntervalSeconds(), config.heartbeatTimeoutSeconds());
            HttpRouter registrationRouter = new HttpRouter();
            registrationRouter.register("POST", "/api/v1/register", new RegistrationHandler(users));
            registrationRouter.register("POST", "/api/v1/worlds", new WorldCreateHandler(worlds));
            registration = new WorldRelayHttpServer(new InetSocketAddress("127.0.0.1", config.registrationPort()),
                config.maxConcurrentRequests(), config.shutdownGraceSeconds(), registrationRouter, LocalRegistrationHandler::new);
            // 先占用注册端口，避免业务端口为 0 时恰好选中配置的注册端口。
            registration.start();
            api = new WorldRelayHttpServer(new InetSocketAddress(config.bindAddress(), config.port()),
                config.maxConcurrentRequests(), config.shutdownGraceSeconds(), WorldRelayServer.createRouter(worlds),
                next -> new BasicAuthenticationHandler(users, next));
            api.start();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public synchronized InetSocketAddress apiAddress() {
        ensureRunning();
        return api.address();
    }

    public synchronized InetSocketAddress registrationAddress() {
        ensureRunning();
        return registration.address();
    }

    private void ensureRunning() {
        if (closed || api == null) {
            throw new IllegalStateException("Service is not running");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (api != null) {
                api.close();
            }
        } finally {
            try {
                if (registration != null) {
                    registration.close();
                }
            } finally {
                try {
                    if (worlds != null) {
                        worlds.close();
                    }
                } finally {
                    if (users != null) {
                        users.close();
                    }
                }
            }
        }
    }

}
