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
        long committed = runtime.totalMemory();
        long used = Math.max(0, committed - runtime.freeMemory());
        var stats = jobs.stats(null);
        var writer = new JsonWriter().beginObject().name("version").value(version)
            .name("javaVersion").value(System.getProperty("java.version"))
            .name("nativeImage").value(System.getProperty("org.graalvm.nativeimage.kind") != null)
            .name("startedAt").value(startedAt.toString()).name("uptimeMillis").value((System.nanoTime() - started) / 1_000_000)
            .name("worlds").value(worlds.list().size()).name("users").value(users.list().size())
            .name("heap").beginObject().name("usedBytes").value(used)
            .name("committedBytes").value(committed).name("maxBytes").value(runtime.maxMemory()).endObject()
            .name("tunnel").beginObject().name("mode").value(config.tunnel().mode().name().toLowerCase(java.util.Locale.ROOT))
            .name("connections").value(tunnels.connectionCount()).name("hosts").value(tunnels.hostCount()).endObject()
            .name("tasks");
        WorldManagementHandler.writeTasks(writer, stats);
        writer.name("completedOperations").value(stats.completed()).name("failedOperations").value(stats.failed())
            .name("cancelledOperations").value(stats.cancelled()).name("storage").beginObject();
        storage(writer, "worlds", config.worldsDirectory());
        storage(writer, "transfers", config.ssh().transferDirectory());
        String response = writer.endObject().endObject().finish();
        HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8", response.getBytes(StandardCharsets.UTF_8));
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
