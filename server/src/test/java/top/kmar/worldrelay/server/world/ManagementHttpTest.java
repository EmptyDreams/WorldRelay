package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.ServerConfig;
import top.kmar.worldrelay.server.WorldRelayService;
import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.auth.TransferTestUsers;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class ManagementHttpTest {

    @TempDir
    Path root;

    private ServerConfig config() {
        Path users = root.resolve("users.json");
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0, users, root.resolve("worlds"), 10, 30,
            new SshRegistrationConfig(root.resolve("ssh/authorized_keys"), root.resolve("transfers"), "/usr/bin/rrsync", "work", root.resolve("host.pub")),
            TunnelConfig.defaults(users, 0), new RetentionConfig(5, 7, 24, 0, 60, ZoneOffset.UTC));
    }

    private String users() throws Exception {
        Files.writeString(root.resolve("host.pub"), TransferTestUsers.hostPublicKey());
        try (var users = TransferTestUsers.open(config().usersFile(), config().ssh())) {
            var dev = Objects.requireNonNull(users.register("Dev", false));
            assertNotNull(users.register("Guest", false));
            return "Basic " + Base64.getEncoder().encodeToString(("Dev:" + dev.token()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private HttpResponse<String> request(HttpClient client, int port, String path, Map<String, Object> body, String auth) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/" + path)).timeout(Duration.ofSeconds(10));
        if (auth != null) builder.header("Authorization", auth);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(encode(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("BusyWait") // 真实 202 文件任务以短暂等待轮询，避免占满 CPU。
    private Map<String, Object> call(HttpClient client, int port, String path, Map<String, Object> body, String auth, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        HttpResponse<String> response;
        do {
            response = request(client, port, path, body, auth);
            if (response.statusCode() != 202) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, response.statusCode(), () -> "Unexpected management response for " + path);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
        return response.body().isEmpty() ? Map.of() : object(read(new JsonReader(response.body(), 1024 * 1024, 12)));
    }

    private static String encode(Map<String, Object> body) {
        var writer = new JsonWriter().beginObject();
        body.forEach((name, value) -> {
            writer.name(name);
            switch (value) {
                case null -> writer.nullValue();
                case Number number -> writer.value(number.longValue());
                case Boolean flag -> writer.value(flag);
                case String text -> writer.value(text);
                default -> throw new AssertionError("Unexpected test request value");
            }
        });
        return writer.endObject().finish();
    }

    private static Object read(JsonReader reader) {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                Map<String, Object> result = new HashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    result.put(reader.nextName(), read(reader));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                List<Object> result = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    result.add(read(reader));
                }
                reader.endArray();
                yield result;
            }
            case STRING -> reader.nextString();
            case NUMBER -> reader.nextLong();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> {
                reader.nextNull();
                yield null;
            }
            default -> throw new AssertionError("Invalid response JSON");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> acquire(HttpClient client, WorldRelayService service, String world, String auth) throws Exception {
        String device = BackupTestWorld.id();
        var granted = call(client, service.apiAddress().getPort(), "worlds/acquire",
            Map.of("worldId", world, "deviceId", device, "requestId", BackupTestWorld.id()), auth, 201);
        return Map.of("worldId", world, "deviceId", device, "sessionId", granted.get("sessionId"), "epoch", granted.get("epoch"));
    }

    /** 验证详情的上一持有者及分类占用；历史备份删除需确认、拒绝过时任期并支持重启后重试。 */
    @Test
    void measuresStorageAndDeletesOnlyConfirmedHistoricalBackups() throws Exception {
        var data = BackupTestWorld.create(config().worldsDirectory());
        String auth = users();
        Path temporary = data.directory().resolve(".candidate-storage");
        Files.createDirectories(temporary);
        Files.writeString(temporary.resolve("payload"), "tmp");
        Path transfer = config().ssh().transferDirectory().resolve(".sessions").resolve("a".repeat(64)).resolve(data.world()).resolve("1/upload-test");
        Files.createDirectories(transfer);
        Files.writeString(transfer.resolve("payload"), "xfer!");
        Map<String, Object> deletion;
        try (var client = HttpClient.newHttpClient()) {
            try (var service = new WorldRelayService(config())) {
                service.start();
                int admin = service.registrationAddress().getPort();
                var detail = call(client, admin, "worlds/detail", Map.of("worldId", data.world()), null, 200);
                assertEquals("Dev", object(detail.get("world")).get("previousOwner"));
                var storage = object(detail.get("storage"));
                assertEquals(3L, storage.get("temporaryBytes"));
                assertEquals(5L, storage.get("transferBytes"));
                assertTrue((long) storage.get("currentBytes") > 0);
                assertTrue((long) storage.get("backupBytes") > 0);
                assertEquals((long) storage.get("currentBytes") + (long) storage.get("backupBytes") + 8L + (long) storage.get("metadataBytes"), storage.get("totalBytes"));
                call(client, service.apiAddress().getPort(), "worlds/backup-delete-preview", Map.of("worldId", data.world(), "backupVersion", 1), auth, 404);
                call(client, admin, "worlds/backup-delete-preview", Map.of("worldId", data.world(), "backupVersion", 2), null, 409);
                var stale = call(client, admin, "worlds/backup-delete-preview", Map.of("worldId", data.world(), "backupVersion", 1), null, 200);
                call(client, admin, "worlds/backup-delete", stale, null, 400);
                acquire(client, service, data.world(), auth);
                stale.put("confirm", true);
                call(client, admin, "worlds/backup-delete", stale, null, 409);
                assertTrue(Files.isDirectory(data.snapshot(data.first())));
                var abandon = call(client, admin, "worlds/abandon-preview", Map.of("worldId", data.world()), null, 200);
                abandon.put("confirm", true);
                abandon.put("reason", "删除备份测试");
                call(client, admin, "worlds/abandon", abandon, null, 200);
                deletion = call(client, admin, "worlds/backup-delete-preview", Map.of("worldId", data.world(), "backupVersion", 1), null, 200);
                deletion.put("confirm", true);
                var forged = new HashMap<>(deletion);
                forged.put("snapshotId", data.second());
                call(client, admin, "worlds/backup-delete", forged, null, 409);
                assertEquals(true, call(client, admin, "worlds/backup-delete", deletion, null, 200).get("deleted"));
                assertFalse(Files.exists(data.snapshot(data.first())));
                assertTrue(Files.isDirectory(data.snapshot(data.second())));
                assertEquals(1, ((List<?>) call(client, admin, "worlds/backups", Map.of("worldId", data.world()), null, 200).get("backups")).size());
            }
            try (var service = new WorldRelayService(config())) {
                service.start();
                assertEquals(true, call(client, service.registrationAddress().getPort(), "worlds/backup-delete", deletion, null, 200).get("deleted"));
                var detail = call(client, service.registrationAddress().getPort(), "worlds/detail", Map.of("worldId", data.world()), null, 200);
                assertEquals(2L, object(detail.get("world")).get("committedVersion"));
                assertEquals(deletion.get("expectedEpoch"), object(detail.get("world")).get("epoch"));
            }
        }
    }

    /** 验证管理查询、改名、按任期分页历史、带确认的删除及重启幂等；所有快照先进入回收目录。 */
    @Test
    void queriesRenamesHistoriesAndDeletesWithoutAPlayerToken() throws Exception {
        var data = BackupTestWorld.create(config().worldsDirectory());
        String auth = users();
        Map<String, Object> deletion;
        Map<String, Object> deleted;
        try (var client = HttpClient.newHttpClient()) {
            try (var service = new WorldRelayService(config())) {
                service.start();
                int admin = service.registrationAddress().getPort();
                assertEquals(1, ((List<?>) call(client, admin, "worlds", null, null, 200).get("worlds")).size());
                var detail = call(client, admin, "worlds/detail", Map.of("worldId", data.world()), null, 200);
                assertEquals(2L, object(detail.get("world")).get("committedVersion"));
                assertNull(object(detail.get("world")).get("owner"));
                var status = call(client, admin, "status", null, null, 200);
                assertNotEquals("unknown", status.get("version"));
                assertEquals(1L, status.get("worlds"));
                assertEquals(0L, status.get("ownedWorlds"));
                assertEquals(2L, status.get("users"));
                assertEquals(0L, status.get("onlineUsers"));
                assertTrue((long) object(object(status.get("system")).get("cpu")).get("availableProcessors") > 0);
                assertTrue((long) object(status.get("heap")).get("maxBytes") > 0);
                assertTrue((long) object(status.get("heap")).get("usedBytes") >= 0);
                assertTrue((long) object(status.get("heap")).get("usedBytes") <= (long) object(status.get("heap")).get("committedBytes"));
                assertTrue((long) object(object(status.get("storage")).get("worlds")).get("usableBytes") > 0);
                for (String path : List.of("status", "users", "worlds/detail", "worlds/history", "worlds/delete", "users/disable")) {
                    call(client, service.apiAddress().getPort(), path, path.contains("/") ? Map.of() : null, auth, 404);
                }
                var page = call(client, admin, "worlds/history", Map.of("worldId", data.world(), "limit", 1), null, 200);
                assertEquals(2L, page.get("nextBeforeEpoch"));
                assertEquals(true, page.get("hasMore"));
                var last = call(client, admin, "worlds/history", Map.of("worldId", data.world(), "limit", 1, "beforeEpoch", 2), null, 200);
                assertEquals(false, last.get("hasMore"));
                assertEquals(1L, object(((List<?>) last.get("events")).getFirst()).get("epoch"));
                var oldPreview = call(client, admin, "worlds/delete-preview", Map.of("worldId", data.world()), null, 200);
                call(client, admin, "worlds/delete", oldPreview, null, 400);
                var renamed = call(client, admin, "worlds/rename", Map.of("worldId", data.world(), "expectedName", "管理恢复测试", "name", "改名后"), null, 200);
                assertEquals("改名后", object(renamed.get("world")).get("name"));
                assertEquals(2L, object(renamed.get("world")).get("epoch"));
                oldPreview.put("confirm", true);
                call(client, admin, "worlds/delete", oldPreview, null, 409);
                var owner = acquire(client, service, data.world(), auth);
                assertEquals(1L, call(client, admin, "status", null, null, 200).get("ownedWorlds"));
                call(client, admin, "worlds/delete-preview", Map.of("worldId", data.world()), null, 409);
                var abandon = call(client, admin, "worlds/abandon-preview", Map.of("worldId", data.world()), null, 200);
                abandon.put("confirm", true);
                call(client, admin, "worlds/abandon", abandon, null, 200);
                assertEquals(0L, call(client, admin, "status", null, null, 200).get("ownedWorlds"));
                var restore = call(client, admin, "worlds/restore-preview", Map.of("worldId", data.world(), "backupVersion", 1), null, 200);
                restore.put("confirm", true);
                call(client, admin, "worlds/restore", restore, null, 200);
                var history = call(client, admin, "worlds/history", Map.of("worldId", data.world()), null, 200);
                var types = ((List<?>) history.get("events")).stream().map(value -> object(value).get("type")).toList();
                assertTrue(types.containsAll(List.of("COMMIT", "RESTORE", "ABANDON")));
                deletion = call(client, admin, "worlds/delete-preview", Map.of("worldId", data.world()), null, 200);
                deletion.put("confirm", true);
                deleted = call(client, admin, "worlds/delete", deletion, null, 200);
                assertEquals(deleted, call(client, admin, "worlds/delete", deletion, null, 200));
                call(client, admin, "worlds/detail", Map.of("worldId", data.world()), null, 404);
                call(client, service.apiAddress().getPort(), "worlds/heartbeat", owner, auth, 404);
                assertFalse(Files.exists(data.directory()));
                Path archive = data.root().resolve(".deleted-" + data.world() + "-" + deletion.get("deleteId"));
                assertEquals("first-world", Files.readString(archive.resolve("snapshots").resolve(data.first()).resolve("world/level.dat")));
            }
            try (var service = new WorldRelayService(config())) {
                service.start();
                assertEquals(deleted, call(client, service.registrationAddress().getPort(), "worlds/delete", deletion, null, 200));
                assertEquals(List.of(), call(client, service.registrationAddress().getPort(), "worlds", null, null, 200).get("worlds"));
            }
        }
    }

    /** 禁用会撤销临时 SSH 授权并清掉缓存票据，重启仍禁用；启用可继续原任期，锁不会自动释放。 */
    @Test
    void disablesUsersRevokesTransferTicketsAndKeepsOwnedWorlds() throws Exception {
        var data = BackupTestWorld.create(config().worldsDirectory());
        String auth = users();
        Map<String, Object> owner;
        try (var client = HttpClient.newHttpClient()) {
            try (var service = new WorldRelayService(config())) {
                service.start();
                int api = service.apiAddress().getPort(), admin = service.registrationAddress().getPort();
                owner = acquire(client, service, data.world(), auth);
                var stopping = new HashMap<>(owner);
                stopping.put("stage", "BEGIN");
                call(client, api, "worlds/stop", stopping, auth, 200);
                var transfer = new HashMap<>(owner);
                transfer.put("baseVersion", 2);
                transfer.put("uploadId", BackupTestWorld.id());
                var ticket = call(client, api, "worlds/upload", transfer, auth, 200);
                assertTrue(Files.readString(config().ssh().authorizedKeysFile()).contains((String) ticket.get("transferId")));
                call(client, admin, "users/disable", Map.of("username", "Dev"), null, 200);
                assertFalse(Files.readString(config().ssh().authorizedKeysFile()).contains((String) ticket.get("transferId")));
                call(client, api, "worlds/heartbeat", owner, auth, 401);
                var detail = object(call(client, admin, "worlds/detail", Map.of("worldId", data.world()), null, 200).get("world"));
                assertEquals("Dev", object(detail.get("owner")).get("username"));
                assertEquals(owner.get("epoch"), detail.get("epoch"));
                assertEquals(1L, call(client, admin, "status", null, null, 200).get("ownedWorlds"));
                String listed = request(client, admin, "users", null, null).body();
                assertFalse(listed.contains("tokenHash"));
                assertFalse(listed.contains("privateKey"));
                assertFalse(listed.contains("\"token\""));
                call(client, admin, "users/enable", Map.of("username", "Dev"), null, 200);
                assertEquals(owner.get("epoch"), call(client, api, "worlds/heartbeat", owner, auth, 200).get("epoch"));
                var renewed = call(client, api, "worlds/upload", transfer, auth, 200);
                assertNotEquals(ticket.get("privateKey"), renewed.get("privateKey"));
                call(client, admin, "users/disable", Map.of("username", "Dev"), null, 200);
            }
            try (var service = new WorldRelayService(config())) {
                service.start();
                int admin = service.registrationAddress().getPort();
                call(client, service.apiAddress().getPort(), "worlds", null, auth, 401);
                var detail = object(call(client, admin, "worlds/detail", Map.of("worldId", data.world()), null, 200).get("world"));
                assertEquals(owner.get("epoch"), detail.get("epoch"));
                call(client, admin, "users/enable", Map.of("username", "missing"), null, 404);
                call(client, admin, "worlds/history", Map.of("worldId", data.world(), "limit", 201), null, 400);
                call(client, admin, "worlds/rename", Map.of("worldId", data.world(), "name", "bad"), null, 400);
            }
        }
    }

}
