package top.kmar.worldrelay.server;

import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.http.*;
import top.kmar.worldrelay.server.tunnel.TunnelGateway;
import top.kmar.worldrelay.server.world.WorldBackups;
import top.kmar.worldrelay.server.world.WorldRetention;
import top.kmar.worldrelay.server.world.WorldStore;
import top.kmar.worldrelay.server.world.WorldTransfers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;

/** 统一管理双监听器与用户、世界存储锁；启动失败时整体回滚。 */
public final class WorldRelayService implements AutoCloseable {

    private final ServerConfig config;
    private UserStore users;
    private WorldStore worlds;
    private WorldRelayHttpServer api;
    private WorldRelayHttpServer registration;
    private TransferJobs transferJobs;
    private WorldRetention retention;
    private TunnelGateway tunnels;
    private final UserStoreFactory userStoreFactory;
    private boolean closed;

    public WorldRelayService(ServerConfig config) {
        this(config, UserStore::open);
    }

    WorldRelayService(ServerConfig config, UserStoreFactory userStoreFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.userStoreFactory = Objects.requireNonNull(userStoreFactory);
    }

    @FunctionalInterface
    interface UserStoreFactory {

        UserStore open(Path path, SshRegistrationConfig ssh) throws IOException;

    }

    public synchronized void start() throws IOException {
        if (closed || users != null) {
            throw new IllegalStateException("Service cannot be started again");
        }
        try {
            config.createDirectories();
            users = userStoreFactory.open(config.usersFile(), config.ssh());
            worlds = WorldStore.open(config.worldsDirectory(), config.heartbeatIntervalSeconds(), config.heartbeatTimeoutSeconds());
            worlds.configureUsers(users::isEnabled);
            retention = new WorldRetention(worlds, config.ssh().transferDirectory(), config.retention());
            transferJobs = new TransferJobs();
            tunnels = new TunnelGateway(worlds, config.tunnel());
            var backups = new WorldBackups(worlds, retention);
            HttpRouter registrationRouter = new HttpRouter();
            registrationRouter.register("POST", "/api/v1/register", new RegistrationHandler(users));
            registrationRouter.register("POST", "/api/v1/worlds", new WorldCreateHandler(worlds));
            for (var action : WorldManagementHandler.Action.values()) {
                registrationRouter.register(action == WorldManagementHandler.Action.LIST ? "GET" : "POST",
                    "/api/v1/worlds" + (action == WorldManagementHandler.Action.LIST ? "" : "/" + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
                    new WorldManagementHandler(worlds, retention, users, transferJobs, config.retention().temporaryHours(), action));
            }
            for (var action : UserManagementHandler.Action.values()) {
                registrationRouter.register(action == UserManagementHandler.Action.LIST ? "GET" : "POST",
                    "/api/v1/users" + (action == UserManagementHandler.Action.LIST ? "" : "/" + action.name().toLowerCase(java.util.Locale.ROOT)),
                    new UserManagementHandler(users, worlds, tunnels, transferJobs, action));
            }
            registrationRouter.register("GET", "/api/v1/status", new ServerStatusHandler(config, users, worlds, tunnels, transferJobs));
            for (var action : WorldBackupHandler.Action.values()) {
                registrationRouter.register("POST", "/api/v1/worlds/" + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    new WorldBackupHandler(backups, action, transferJobs));
            }
            for (var action : WorldAbandonHandler.Action.values()) {
                registrationRouter.register("POST", "/api/v1/worlds/" + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    new WorldAbandonHandler(worlds, action));
            }
            registration = new WorldRelayHttpServer(new InetSocketAddress("127.0.0.1", config.registrationPort()),
                config.maxConcurrentRequests(), config.shutdownGraceSeconds(), registrationRouter, LocalRegistrationHandler::new);
            // 先占用注册端口，避免业务端口为 0 时恰好选中配置的注册端口。
            registration.start();
            tunnels.start();
            HttpRouter apiRouter = WorldRelayServer.createRouter(worlds, tunnels);
            apiRouter.register("POST", "/api/v1/worlds/tunnel-ticket", new TunnelTicketHandler(tunnels));
            var transfers = new WorldTransfers(worlds, users, config.ssh(), retention);
            for (WorldTransferHandler.Action action : WorldTransferHandler.Action.values()) {
                apiRouter.register("POST", "/api/v1/worlds/" + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    new WorldTransferHandler(transfers, action, transferJobs));
            }
            api = new WorldRelayHttpServer(new InetSocketAddress(config.bindAddress(), config.port()),
                config.maxConcurrentRequests(), config.shutdownGraceSeconds(), apiRouter,
                next -> new BasicAuthenticationHandler(users, next));
            api.start();
            retention.start();
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

    public synchronized InetSocketAddress tunnelAddress() {
        ensureRunning();
        return tunnels.address();
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
                        if (tunnels != null) tunnels.close();
                        if (transferJobs != null) transferJobs.close();
                        if (retention != null) retention.close();
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
