package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.ServerConfig;
import top.kmar.worldrelay.server.WorldRelayService;
import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.tunnel.TunnelConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class WorldBackupHttpTest {

    @TempDir
    Path root;

    private ServerConfig config(BackupTestWorld data) {
        Path users = root.resolve("users.json");
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0, users, data.root(), 10, 30,
            SshRegistrationConfig.defaults(users), TunnelConfig.defaults(users, 0),
            new RetentionConfig(5, 7, 24, 0, 60, ZoneOffset.UTC));
    }

    private HttpResponse<String> post(HttpClient client, int port, String action, String body, String auth) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/worlds/" + action))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (auth != null) builder.header("Authorization", auth);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("BusyWait") // 管理端异步接口需要按 202 轮询，超时限定为 10 秒。
    private HttpResponse<String> restore(HttpClient client, WorldRelayService service, String body) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        HttpResponse<String> response;
        do {
            response = post(client, service.registrationAddress().getPort(), "restore", body, null);
            if (response.statusCode() == 202) Thread.sleep(10);
        } while (response.statusCode() == 202 && System.nanoTime() < deadline);
        return response;
    }

    /** 本机管理端可列出、预览并明确确认恢复，后台轮询和重启后重试幂等，业务端不暴露这些接口。 */
    @Test
    void managesRestoreOnlyOnLocalListenerAndRetriesAfterRestart() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        var config = config(data);
        String auth;
        try (var users = UserStore.open(config.usersFile())) {
            var user = users.register("Dev", false);
            assertNotNull(user);
            auth = "Basic " + Base64.getEncoder().encodeToString(("Dev:" + user.token()).getBytes(StandardCharsets.UTF_8));
        }
        String worldBody = "{\"worldId\":\"" + data.world() + "\"}";
        String confirmed, result;
        try (var client = HttpClient.newHttpClient()) {
            try (var service = new WorldRelayService(config)) {
                service.start();
                int local = service.registrationAddress().getPort();
                var list = post(client, local, "backups", worldBody, null);
                assertEquals(200, list.statusCode());
                assertEquals("no-store", list.headers().firstValue("Cache-Control").orElseThrow());
                assertTrue(list.body().contains("\"currentVersion\":2"));
                assertTrue(list.body().contains("\"version\":1"));
                assertEquals(404, post(client, service.apiAddress().getPort(), "backups", worldBody, auth).statusCode());
                var preview = post(client, local, "restore-preview",
                    "{\"worldId\":\"" + data.world() + "\",\"backupVersion\":1}", null);
                assertEquals(200, preview.statusCode());
                assertTrue(preview.body().contains("\"confirm\":false"));
                assertTrue(preview.body().contains("\"expectedVersion\":2"));
                var unconfirmed = post(client, local, "restore", preview.body(), null);
                assertEquals(400, unconfirmed.statusCode());
                assertTrue(unconfirmed.body().contains("RESTORE_CONFIRMATION_REQUIRED"));
                confirmed = preview.body().replace("\"confirm\":false", "\"confirm\":true");
                var restored = restore(client, service, confirmed);
                assertEquals(200, restored.statusCode(), restored.body());
                result = restored.body();
                assertTrue(result.contains("\"version\":3"));
                assertTrue(result.contains("\"backupVersion\":1"));
                assertEquals(result, restore(client, service, confirmed).body());
                assertEquals(404, post(client, local, "restore-preview",
                    "{\"worldId\":\"" + data.world() + "\",\"backupVersion\":999}", null).statusCode());
                assertEquals(413, post(client, local, "backups", " ".repeat(4097), null).statusCode());
            }
            try (var service = new WorldRelayService(config)) {
                service.start();
                assertEquals(result, restore(client, service, confirmed).body());
            }
        }
        var metadata = WorldMetadata.parse(Files.readString(data.metadata()));
        assertEquals(3L, metadata.state().committedVersion());
        assertEquals(1L, metadata.commits().getLast().restoredFromVersion());
    }

}