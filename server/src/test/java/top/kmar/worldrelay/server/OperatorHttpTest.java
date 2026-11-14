package top.kmar.worldrelay.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.TransferTestUsers;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class OperatorHttpTest {
    @TempDir Path root;

    private ServerConfig config() {
        Path users = root.resolve("users.json");
        return new ServerConfig("127.0.0.1", 0, 32, 0, 0, users, root.resolve("worlds"), 10, 30, TransferTestUsers.config(users));
    }

    private static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    private static String encode(Map<String, Object> body) {
        var writer = new JsonWriter().beginObject();
        body.forEach((name, value) -> {
            writer.name(name);
            if (value == null) writer.nullValue();
            else if (value instanceof Number number) writer.value(number.longValue());
            else writer.value(value.toString());
        });
        return writer.endObject().finish();
    }

    private HttpResponse<String> call(HttpClient client, int port, String path, Map<String, Object> body, String auth, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/" + path)).timeout(Duration.ofSeconds(10));
        if (auth != null) request.header("Authorization", auth);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(encode(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expected, response.statusCode(), () -> path + ": " + response.body());
        return response;
    }

    @Test
    void managesGlobalPolicyLocallyAndSyncsOnlyToCurrentAuthenticatedHost() throws Exception {
        String auth;
        String guestAuth;
        try (var users = TransferTestUsers.open(config().usersFile(), config().ssh())) {
            var registered = users.register("Dev", false);
            assertNotNull(registered);
            auth = "Basic " + Base64.getEncoder().encodeToString(("Dev:" + registered.token()).getBytes(StandardCharsets.UTF_8));
            var guest = users.register("Guest", false);
            assertNotNull(guest);
            guestAuth = "Basic " + Base64.getEncoder().encodeToString(("Guest:" + guest.token()).getBytes(StandardCharsets.UTF_8));
        }
        try (var client = HttpClient.newHttpClient()) {
            try (var service = new WorldRelayService(config(), TransferTestUsers::open)) {
                service.start();
                int admin = service.registrationAddress().getPort(), api = service.apiAddress().getPort();
                var set = Map.<String, Object>of("username", "Dev", "level", 2, "expectedRevision", 0);
                call(client, api, "operators/set", set, auth, 404);
                call(client, api, "worlds/operators", Map.of(), null, 401);
                assertTrue(call(client, admin, "operators/set", set, null, 200).body().contains("\"revision\":1"));
                call(client, admin, "operators/set", set, null, 409);
                assertTrue(call(client, admin, "users", null, null, 200).body().contains("\"opLevel\":2"));

                String world = field(call(client, admin, "worlds", Map.of("name", "Operators"), null, 201).body(), "worldId");
                String device = UUID.randomUUID().toString();
                var acquired = call(client, api, "worlds/acquire", Map.of("worldId", world, "deviceId", device,
                    "requestId", UUID.randomUUID().toString()), auth, 201);
                Map<String, Object> identity = new HashMap<>(Map.of("worldId", world, "deviceId", device,
                    "sessionId", field(acquired.body(), "sessionId"), "epoch", 1));
                Map<String, Object> ready = new HashMap<>(identity);
                ready.put("stage", "SAVE_SYNCED");
                ready.put("baseVersion", null);
                call(client, api, "worlds/ready", ready, auth, 200);
                identity.put("appliedRevision", -1);
                call(client, api, "worlds/operators", identity, guestAuth, 403);
                assertTrue(call(client, api, "worlds/operators", identity, auth, 200).body().contains("\"level\":2"));
                identity.put("appliedRevision", 1);
                String unchanged = call(client, api, "worlds/operators", identity, auth, 200).body();
                assertTrue(unchanged.contains("\"unchanged\":true"));
                assertFalse(unchanged.contains("\"operators\""));
                var stale = new HashMap<>(identity);
                stale.put("epoch", 2);
                call(client, api, "worlds/operators", stale, auth, 409);
                var other = new HashMap<>(identity);
                other.put("deviceId", UUID.randomUUID().toString());
                call(client, api, "worlds/operators", other, auth, 403);

                call(client, admin, "operators/remove", Map.of("username", "Dev", "expectedRevision", 1), null, 200);
                assertTrue(call(client, api, "worlds/operators", identity, auth, 200).body().contains("\"operators\":[]"));
            }
            try (var service = new WorldRelayService(config(), TransferTestUsers::open)) {
                service.start();
                String response = call(client, service.registrationAddress().getPort(), "operators", null, null, 200).body();
                assertTrue(response.contains("\"revision\":2"));
                assertTrue(response.contains("\"operators\":[]"));
            }
        }
    }
}
