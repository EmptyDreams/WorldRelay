package top.kmar.worldrelay.server;

import top.kmar.worldrelay.server.http.HttpRouter;
import top.kmar.worldrelay.server.http.WorldRelayHttpServer;

/** 独立协调服务的启动入口，后续在此注册业务路由。 */
public final class WorldRelayServer {

    private WorldRelayServer() {
    }

    public static void main(String[] args) {
        System.Logger logger = System.getLogger(WorldRelayServer.class.getName());
        try {
            ServerConfig config = ServerConfig.fromArgs(args);
            WorldRelayHttpServer server = new WorldRelayHttpServer(config, new HttpRouter());
            Thread shutdownHook = new Thread(server::close, "worldrelay-http-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                server.start();
            } catch (Exception failure) {
                server.close();
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                throw failure;
            }
            logger.log(System.Logger.Level.INFO, "WorldRelay HTTP listening on {0}", server.address());
        } catch (Exception failure) {
            logger.log(System.Logger.Level.ERROR, "Unable to start WorldRelay HTTP server", failure);
            System.exit(1);
        }
    }

}