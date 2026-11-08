package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.world.WorldAbandonment;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldPhase;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 只挂在本机管理监听器；不会把撤销玩家所有权的能力加入业务端。 */
public final class WorldAbandonHandler implements HttpHandler {

    public enum Action {ABANDON_PREVIEW, ABANDON}

    private final WorldStore worlds;
    private final Action action;

    public WorldAbandonHandler(WorldStore worlds, Action action) {
        this.worlds = worlds;
        this.action = action;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpRequests.rejectNonJsonContentType(exchange)) return;
        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) {
            exchange.getResponseHeaders().set("Connection", "close");
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "Abandonment request exceeds 4096 bytes");
            return;
        }
        try {
            var reader = new JsonReader(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), 4096, 2);
            String world = null, abandonId = null, username = null, at = null, reason = null;
            long epoch = 0;
            Long version = null;
            WorldPhase phase = null;
            boolean confirm = false;
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                fields.add(field);
                switch (field) {
                    case "worldId" -> world = reader.nextString();
                    case "abandonId" -> abandonId = reader.nextString();
                    case "ownerUsername" -> username = reader.nextString();
                    case "expectedEpoch" -> epoch = reader.nextLong();
                    case "expectedVersion" -> {
                        if (reader.peek() == JsonToken.NULL) reader.nextNull();
                        else version = reader.nextLong();
                    }
                    case "expectedPhase" -> phase = WorldPhase.valueOf(reader.nextString());
                    case "lastCommittedAt" -> {
                        if (reader.peek() == JsonToken.NULL) reader.nextNull();
                        else at = reader.nextString();
                    }
                    case "reason" -> reason = reader.nextString();
                    case "confirm" -> confirm = reader.nextBoolean();
                    default -> throw new IllegalArgumentException("Unknown abandonment request field");
                }
            }
            reader.endObject();
            reader.finish();
            Set<String> expected = action == Action.ABANDON_PREVIEW ? Set.of("worldId")
                : Set.of("worldId", "abandonId", "ownerUsername", "expectedEpoch", "expectedVersion", "expectedPhase", "lastCommittedAt", "reason", "confirm");
            if (!fields.equals(expected)) throw new IllegalArgumentException("Missing abandonment request field");
            WorldStore.validateId(world);
            String response;
            if (action == Action.ABANDON_PREVIEW) response = worlds.previewAbandon(world).toJson(false);
            else {
                if (!confirm)
                    throw new WorldAccessException(400, "ABANDON_CONFIRMATION_REQUIRED", "Review the retained version and explicitly confirm losing unuploaded progress");
                var request = new WorldAbandonment.Request(world, abandonId, epoch, version, phase, username, at, reason);
                response = worlds.abandon(request).toJson();
            }
            HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8", response.getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_ABANDON_REQUEST", "Invalid abandonment management request");
        } catch (IOException failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Abandonment metadata publication failed; restart if the result is uncertain");
            HttpResponses.error(exchange, 503, "ABANDON_STORAGE_FAILED", "Abandonment was not acknowledged; retry the same request after server recovery");
        }
    }

}