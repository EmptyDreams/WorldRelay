package top.kmar.worldrelay.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.auth.TransferTestUsers;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.world.WorldStore;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class WorldTransferHttpTest {

    @TempDir
    Path root;

    private ServerConfig config() {
        var ssh = new SshRegistrationConfig(root.resolve("ssh/authorized_keys"), root.resolve("transfers"),
            root.resolve("rrsync-not-required").toString(), "work", root.resolve("host.pub"), "files.example", 2222);
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0, root.resolve("users.json"), root.resolve("worlds"), 5, 20, ssh);
    }

    /** 验证真实 HTTP 上传任务的轮询、提交，以及重启后仍可查询的提交回执。 */
    @Test
    void realHttpLifecyclePollsLargeJobsAndRecoversPublishedReceiptAfterRestart() throws Exception {
        String key, world, device = UUID.randomUUID().toString(), request = UUID.randomUUID().toString(), upload = UUID.randomUUID().toString();
        try (var users = UserStore.open(config().usersFile(), config().ssh()); var worlds = WorldStore.open(config().worldsDirectory())) {
            var registration = users.register("Alice", false);
            assertNotNull(registration);
            key = registration.token();
            Files.writeString(root.resolve("host.pub"), TransferTestUsers.hostPublicKey());
            var summary = worlds.create("HTTP 同步测试");
            assertNotNull(summary);
            world = summary.worldId();
        }
        String auth = "Basic " + Base64.getEncoder().encodeToString(("Alice:" + key).getBytes(StandardCharsets.UTF_8));
        String fields, hash;
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config(), TransferTestUsers::open)) {
            service.start();
            var acquired = send(client, service, "acquire", "{\"worldId\":\"" + world + "\",\"deviceId\":\"" + device + "\",\"requestId\":\"" + request + "\"}", auth);
            assertEquals(201, acquired.statusCode());
            fields = "\"worldId\":\"" + world + "\",\"deviceId\":\"" + device + "\",\"sessionId\":\"" + field(acquired.body(), "sessionId") + "\",\"epoch\":1";
            String transfer = fields + ",\"baseVersion\":null,\"uploadId\":\"" + upload + "\"";
            assertEquals(401, send(client, service, "upload", "{" + transfer + "}", "Basic invalid").statusCode());
            assertEquals(400, send(client, service, "upload", "{" + transfer + ",\"unknown\":true}", auth).statusCode());
            assertEquals(200, send(client, service, "stop", "{" + fields + ",\"stage\":\"BEGIN\"}", auth).statusCode());
            String expectedHash = "d".repeat(64);
            var before = poll(client, service, "commit-status", "{" + transfer + ",\"manifestHash\":\"" + expectedHash + "\"}", auth);
            assertEquals(200, before.statusCode());
            assertNull(field(before.body(), "version"));
            var ticket = poll(client, service, "upload", "{" + transfer + "}", auth);
            assertEquals(200, ticket.statusCode());
            assertEquals("no-store", ticket.headers().firstValue("Cache-Control").orElseThrow());
            String privateKey = field(ticket.body(), "privateKey");
            assertNotNull(privateKey);
            assertTrue(privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"));
            assertEquals(top.kmar.worldrelay.sync.RsyncCommand.create("files.example", 2222, "work", true), transferCommand(ticket.body()));
            Path payload;
            try (var paths = Files.walk(config().ssh().transferDirectory())) {
                payload = paths.filter(path -> path.getFileName().toString().equals("payload")).findFirst().orElseThrow();
            }
            Files.writeString(payload.resolve("world/level.dat"), "synthetic-level");
            byte[] manifest = SnapshotFiles.scan(payload.resolve("world"), "e".repeat(64)).encode();
            Files.write(payload.resolve("manifest.bin"), manifest);
            hash = SnapshotManifest.hash(manifest);
            var committed = poll(client, service, "commit", "{" + transfer + ",\"manifestHash\":\"" + hash + "\"}", auth);
            assertEquals(200, committed.statusCode());
            assertEquals("1", field(committed.body(), "version"));
            assertEquals(409, send(client, service, "acquire", "{\"worldId\":\"" + world + "\",\"deviceId\":\"" + device + "\",\"requestId\":\"" + request + "\"}", auth).statusCode(),
                "旧获取请求不能在提交后重新取得世界");
        }
        try (var client = HttpClient.newHttpClient(); var service = new WorldRelayService(config(), TransferTestUsers::open)) {
            service.start();
            String body = "{" + fields + ",\"baseVersion\":null,\"uploadId\":\"" + upload + "\",\"manifestHash\":\"" + hash + "\"}";
            var result = poll(client, service, "commit-status", body, auth);
            assertEquals(200, result.statusCode());
            assertEquals("1", field(result.body(), "version"));
        }
    }

    private static HttpResponse<String> send(HttpClient client, WorldRelayService service, String action, String body, String auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.apiAddress().getPort() + "/api/v1/worlds/" + action))
            .timeout(Duration.ofSeconds(5)).header("Authorization", auth).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> poll(HttpClient client, WorldRelayService service, String action, String body, String auth) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var result = send(client, service, action, body, auth);
            if (result.statusCode() != 202) return result;
            // 测试真实 202 轮询，每轮释放 CPU 给文件任务执行。
            Thread.sleep(10);
        }
        throw new AssertionError("Snapshot job did not finish");
    }

    private static top.kmar.worldrelay.sync.RsyncCommand transferCommand(String json) {
        JsonReader reader = new JsonReader(json);
        reader.beginObject();
        while (reader.hasNext()) {
            if (!reader.nextName().equals("rsyncCommand")) {
                reader.skipValue();
                continue;
            }
            String host = null, user = null;
            int port = 0;
            var arguments = new java.util.ArrayList<String>();
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "host" -> host = reader.nextString();
                    case "user" -> user = reader.nextString();
                    case "port" -> port = Math.toIntExact(reader.nextLong());
                    case "arguments" -> {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            arguments.add(reader.nextString());
                        }
                        reader.endArray();
                    }
                    default -> throw new AssertionError("Unexpected rsync command field");
                }
            }
            reader.endObject();
            return new top.kmar.worldrelay.sync.RsyncCommand(host, port, user, arguments);
        }
        throw new AssertionError("Missing rsync command");
    }

    private static String field(String json, String name) {
        JsonReader reader = new JsonReader(json);
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (key.equals(name)) {
                return switch (reader.peek()) {
                    case NULL -> {
                        reader.nextNull();
                        yield null;
                    }
                    case NUMBER -> Long.toString(reader.nextLong());
                    default -> reader.nextString();
                };
            }
            reader.skipValue();
        }
        throw new AssertionError("Missing response field: " + name);
    }

}