package top.kmar.mc.worldreplay.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 世界摘要查询；仅在 IO 线程调用，不获取所有权或打开本地存档。 */
final class SharedWorldListClient {

    private static final Set<String> STATUSES = Set.of("FREE", "RUNNING", "COMMITTING", "RECOVERY_REQUIRED", "OWNED_OFFLINE");

    private SharedWorldListClient() {
    }

    static List<Summary> fetch(ClientConnectionSettings settings, String playerName) throws IOException {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build()) {
            String credentials = Base64.getEncoder().encodeToString(
                (playerName + ":" + settings.token()).getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(settings.serverUri().resolve("/api/v1/worlds"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + credentials)
                .header("Cache-Control", "no-cache").header("Accept", "application/json").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("World list request failed: HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("World list request interrupted");
        } catch (RuntimeException e) {
            // 不传播可能包含服务端内容或配置凭据的异常。
            throw new IOException("Invalid world list or connection settings");
        }
    }

    static List<Summary> parse(String body) throws IOException {
        try {
            var root = new Gson().fromJson(body, JsonObject.class);
            var result = new ArrayList<Summary>();
            var ids = new HashSet<String>();
            for (var element : root.getAsJsonArray("worlds")) {
                var world = element.getAsJsonObject();
                String id = requiredString(world, "worldId");
                String name = requiredString(world, "name");
                String status = requiredString(world, "status");
                if (!ids.add(id) || !STATUSES.contains(status)) {
                    throw new IllegalArgumentException();
                }
                var host = world.get("hostName");
                var count = world.get("playerCount");
                var lastCommittedAt = world.get("lastCommittedAt");
                Integer players = count.isJsonNull() ? null : count.getAsBigDecimal().intValueExact();
                if (players != null && players < 0) {
                    throw new IllegalArgumentException();
                }
                result.add(new Summary(id, name, status, host.isJsonNull() ? null : requiredString(world, "hostName"),
                    players, lastCommittedAt.isJsonNull() ? null : requiredString(world, "lastCommittedAt")));
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new IOException("Invalid world list response");
        }
    }

    private static String requiredString(JsonObject object, String key) {
        var value = object.getAsJsonPrimitive(key);
        if (!value.isString() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException();
        }
        return value.getAsString();
    }

    record Summary(String worldId, String name, String status, String hostName, Integer playerCount,
                   String lastCommittedAt) {

    }

}