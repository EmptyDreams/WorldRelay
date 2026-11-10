package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.ServerConfig;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.tunnel.TunnelGateway;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

/** 运行时及文件系统的轻量快照；堆统计不冒充进程 RSS。 */
public final class ServerStatusHandler implements HttpHandler {

    private final ServerConfig config;
    private final UserStore users;
    private final WorldStore worlds;
    private final TunnelGateway tunnels;
    private final TransferJobs jobs;
    private final Instant startedAt = Instant.now();
    private final long started = System.nanoTime();
    private final String version = version();
    private final SystemMetrics systemMetrics = new SystemMetrics();

    public ServerStatusHandler(ServerConfig config, UserStore users, WorldStore worlds, TunnelGateway tunnels, TransferJobs jobs) {
        this.config = config;
        this.users = users;
        this.worlds = worlds;
        this.tunnels = tunnels;
        this.jobs = jobs;
    }

    private static String version() {
        try (var input = ServerStatusHandler.class.getResourceAsStream("/worldrelay-server.properties")) {
            if (input == null) return "unknown";
            var properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", "unknown");
        } catch (IOException failure) {
            return "unknown";
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var runtime = Runtime.getRuntime();
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.kind") != null;
        var stats = jobs.stats(null);
        var listedWorlds = worlds.list();
        var accounts = users.list();
        var online = tunnels.onlineUsers();
        var writer = new JsonWriter().beginObject().name("version").value(version)
            .name("javaVersion").value(System.getProperty("java.version"))
            .name("nativeImage").value(nativeImage)
            .name("startedAt").value(startedAt.toString()).name("uptimeMillis").value((System.nanoTime() - started) / 1_000_000)
            .name("worlds").value(listedWorlds.size())
            .name("ownedWorlds").value(listedWorlds.stream().filter(world -> world.hostName() != null).count())
            .name("users").value(accounts.size())
            .name("onlineUsers").value(accounts.stream().filter(account -> account.enabled() && online.contains(account.username())).count())
            .name("heap");
        writeHeap(writer, runtime.totalMemory(), runtime.freeMemory(), runtime.maxMemory(), nativeImage);
        writer.name("tunnel").beginObject().name("mode").value(config.tunnel().mode().name().toLowerCase(java.util.Locale.ROOT))
            .name("connections").value(tunnels.connectionCount()).name("hosts").value(tunnels.hostCount()).endObject()
            .name("tasks");
        WorldManagementHandler.writeTasks(writer, stats);
        writer.name("completedOperations").value(stats.completed()).name("failedOperations").value(stats.failed())
            .name("cancelledOperations").value(stats.cancelled()).name("system");
        systemMetrics.write(writer);
        writer.name("storage").beginObject();
        storage(writer, "worlds", config.worldsDirectory());
        storage(writer, "transfers", config.ssh().transferDirectory());
        String response = writer.endObject().endObject().finish();
        HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8", response.getBytes(StandardCharsets.UTF_8));
    }

    /** Native Image 的 Serial GC 将 totalMemory 定义为堆上限，不能将其当作已分配容量。 */
    static void writeHeap(JsonWriter writer, long total, long free, long max, boolean nativeImage) {
        writer.beginObject().name("usedBytes").value(Math.max(0, total - free)).name("committedBytes");
        if (nativeImage) {
            writer.nullValue();
        } else {
            writer.value(total);
        }
        writer.name("maxBytes").value(max).endObject();
    }

    private static void storage(JsonWriter writer, String name, Path configured) {
        writer.name(name);
        try {
            Path existing = configured.toAbsolutePath().normalize();
            while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                writer.nullValue();
                return;
            }
            var store = Files.getFileStore(existing);
            writer.beginObject().name("totalBytes").value(store.getTotalSpace()).name("usableBytes").value(store.getUsableSpace()).endObject();
        } catch (IOException failure) {
            writer.nullValue();
        }
    }

}
