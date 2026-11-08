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
class WorldAbandonHttpTest {

    @TempDir
    Path root;

    private HttpResponse<String> post(HttpClient client, int port, String action, String body, String auth) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/worlds/" + action))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (auth != null) builder.header("Authorization", auth);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 本机管理端要求显式确认并支持有存档和空世界；重启后可重试，业务端不提供撤销能力。 */
    @Test
    void managesExplicitAbandonmentOnlyOnLocalListener() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        String empty;
        try (var store = WorldStore.open(data.root())) {
            BackupTestWorld.acquire(store, data.world());
            var created = store.create("无存档的未完成世界");
            assertNotNull(created);
            empty = created.worldId();
            BackupTestWorld.acquire(store, empty);
        }
        Path usersFile = root.resolve("users.json");
        var config = new ServerConfig("127.0.0.1", 0, 32, 0, 0, usersFile, data.root(), 10, 30,
            SshRegistrationConfig.defaults(usersFile), TunnelConfig.defaults(usersFile, 0),
            new RetentionConfig(5, 7, 24, 0, 60, ZoneOffset.UTC));
        String auth;
        try (var users = UserStore.open(usersFile)) {
            var registered = users.register("Dev", false);
            assertNotNull(registered);
            auth = "Basic " + Base64.getEncoder().encodeToString(("Dev:" + registered.token()).getBytes(StandardCharsets.UTF_8));
        }
        String confirmed, result;
        try (var client = HttpClient.newHttpClient()) {
            try (var service = new WorldRelayService(config)) {
                service.start();
                int local = service.registrationAddress().getPort(), api = service.apiAddress().getPort();
                String body = "{\"worldId\":\"" + data.world() + "\"}";
                var preview = post(client, local, "abandon-preview", body, null);
                assertEquals(200, preview.statusCode(), preview.body());
                assertEquals("no-store", preview.headers().firstValue("Cache-Control").orElseThrow());
                assertTrue(preview.body().contains("\"expectedVersion\":2"));
                assertTrue(preview.body().contains("\"ownerUsername\":\"Dev\""));
                assertTrue(preview.body().contains("\"confirm\":false"));
                var unconfirmed = post(client, local, "abandon", preview.body(), null);
                assertEquals(400, unconfirmed.statusCode());
                assertTrue(unconfirmed.body().contains("ABANDON_CONFIRMATION_REQUIRED"));
                confirmed = preview.body().replace("\"confirm\":false", "\"confirm\":true")
                    .replace("\"reason\":\"\"", "\"reason\":\"管理员确认放弃未上传进度\"");
                assertEquals(404, post(client, api, "abandon-preview", body, auth).statusCode());
                assertEquals(404, post(client, api, "abandon", confirmed, auth).statusCode());
                var abandoned = post(client, local, "abandon", confirmed, null);
                assertEquals(200, abandoned.statusCode(), abandoned.body());
                result = abandoned.body();
                assertTrue(result.contains("\"committedVersion\":2"));
                assertTrue(result.contains("\"epoch\":4"));
                assertEquals(result, post(client, local, "abandon", confirmed, null).body());
                assertEquals(200, post(client, local, "restore-preview", body.substring(0, body.length() - 1) + ",\"backupVersion\":1}", null).statusCode());
                var emptyPreview = post(client, local, "abandon-preview", "{\"worldId\":\"" + empty + "\"}", null);
                assertEquals(200, emptyPreview.statusCode());
                assertTrue(emptyPreview.body().contains("\"expectedVersion\":null"));
                var emptyResult = post(client, local, "abandon", emptyPreview.body().replace("\"confirm\":false", "\"confirm\":true"), null);
                assertEquals(200, emptyResult.statusCode(), emptyResult.body());
                assertTrue(emptyResult.body().contains("\"committedVersion\":null"));
                assertEquals(413, post(client, local, "abandon", " ".repeat(4097), null).statusCode());
            }
            try (var service = new WorldRelayService(config)) {
                service.start();
                assertEquals(result, post(client, service.registrationAddress().getPort(), "abandon", confirmed, null).body());
            }
        }
        var metadata = WorldMetadata.parse(Files.readString(data.metadata()));
        assertEquals(1, metadata.cancellations().size());
        assertEquals(2L, metadata.state().committedVersion());
        assertEquals("管理员确认放弃未上传进度", metadata.cancellations().getFirst().reason());
    }

}