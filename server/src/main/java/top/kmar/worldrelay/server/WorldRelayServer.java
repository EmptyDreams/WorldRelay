package top.kmar.worldrelay.server;

import top.kmar.worldrelay.server.http.HttpResponses;
import top.kmar.worldrelay.server.http.HttpRouter;
import top.kmar.worldrelay.server.http.WorldListHandler;
import top.kmar.worldrelay.server.http.WorldOwnershipHandler;
import top.kmar.worldrelay.server.world.WorldStore;

import java.util.concurrent.CountDownLatch;

/** 独立协调服务的启动入口，负责配置、路由装配和服务生命周期。 */
public final class WorldRelayServer {

    private WorldRelayServer() {
    }

    public static void main(String[] args) {
        System.Logger logger = System.getLogger(WorldRelayServer.class.getName());
        try {
            ServerConfig config = ServerConfig.fromArgs(args);
            try (WorldRelayService server = new WorldRelayService(config)) {
                CountDownLatch stopped = new CountDownLatch(1);
                Thread shutdownHook = new Thread(() -> {
                    try {
                        server.close();
                    } catch (Exception failure) {
                        logger.log(System.Logger.Level.ERROR, "Unable to close WorldRelay service", failure);
                    } finally {
                        stopped.countDown();
                    }
                }, "worldrelay-http-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                try {
                    server.start();
                } catch (Exception failure) {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    throw failure;
                }
                logger.log(System.Logger.Level.INFO, "WorldRelay HTTP listening on {0}", server.apiAddress());
                logger.log(System.Logger.Level.INFO, "WorldRelay registration listening on {0}", server.registrationAddress());
                // 启动后持续托管，避免 main 返回时 try-with-resources 立即关闭两个监听器。
                try {
                    stopped.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception failure) {
            logger.log(System.Logger.Level.ERROR, "Unable to start WorldRelay HTTP server", failure);
            System.exit(1);
        }
    }

    /** 显式装配生产路由，供启动入口和集成测试共同使用。 */
    static HttpRouter createRouter(WorldStore worlds) {
        HttpRouter router = new HttpRouter();
        router.register("GET", "/api/v1/ping", exchange -> {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            HttpResponses.noContent(exchange, 204);
        });
        router.register("GET", "/api/v1/worlds", new WorldListHandler(worlds));
        for (WorldOwnershipHandler.Action action : WorldOwnershipHandler.Action.values()) {
            router.register("POST", "/api/v1/worlds/" + action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                new WorldOwnershipHandler(worlds, action));
        }
        return router;
    }

}
