package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.protocol.HostEndpoint;
import top.kmar.worldrelay.server.protocol.OwnershipView;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 固定协调路由共享有界 JSON 解析；只从鉴权上下文读取用户，不接受客户端指定持有者。 */
public final class WorldOwnershipHandler implements HttpHandler {

    public enum Action {ACQUIRE, HEARTBEAT, READY, STOP, CANCEL_CREATION}

    private static final int MAX_BODY_BYTES = 4096;
    private static final Set<String> IDENTITY_FIELDS = Set.of("worldId", "deviceId", "sessionId", "epoch");
    private final WorldStore worlds;
    private final Action action;

    public WorldOwnershipHandler(WorldStore worlds, Action action) {
        this.worlds = Objects.requireNonNull(worlds);
        this.action = Objects.requireNonNull(action);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (HttpRequests.rejectNonJsonContentType(exchange)) return;
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            exchange.getResponseHeaders().set("Connection", "close");
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "Ownership body exceeds 4096 bytes");
            return;
        }
        Command command;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            command = parse(json);
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_OWNERSHIP_REQUEST", "Invalid ownership request");
            return;
        }
        try {
            String username = exchange.getPrincipal().getUsername();
            OwnershipView view;
            int status = 200;
            if (action == Action.ACQUIRE) {
                var result = worlds.acquire(command.worldId, username, command.deviceId, command.requestId);
                view = result.view();
                status = result.acquired() ? 201 : 200;
            } else {
                var identity = new WorldStore.Identity(username, command.deviceId, command.sessionId, command.epoch);
                if (action == Action.CANCEL_CREATION) {
                    worlds.cancelCreation(command.worldId, identity);
                    HttpResponses.noContent(exchange, 204);
                    return;
                }
                view = switch (action) {
                    case HEARTBEAT -> worlds.heartbeat(command.worldId, identity, command.playerCount);
                    case READY -> worlds.ready(command.worldId, identity, WorldStore.ReadyStage.valueOf(command.stage),
                        command.baseVersion, command.endpoint);
                    case STOP -> worlds.stop(command.worldId, identity, WorldStore.StopStage.valueOf(command.stage), command.baseVersion);
                    case ACQUIRE, CANCEL_CREATION -> throw new IllegalStateException("Already handled action");
                };
            }
            JsonWriter writer = new JsonWriter();
            view.writeJson(writer);
            HttpResponses.bytes(exchange, status, "application/json; charset=utf-8", writer.finish().getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        }
    }

    private record Command(String worldId, String deviceId, String requestId, String sessionId, long epoch,
                           String stage, Long baseVersion, Integer playerCount, HostEndpoint endpoint) {

    }

    private Command parse(String json) {
        JsonReader reader = new JsonReader(json, MAX_BODY_BYTES, 4);
        Set<String> fields = new HashSet<>();
        String world = null, device = null, request = null, session = null, stage = null, address = null;
        long epoch = 0;
        Long base = null;
        Integer players = null;
        int port = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "worldId" -> world = reader.nextString();
                case "deviceId" -> device = reader.nextString();
                case "requestId" -> request = reader.nextString();
                case "sessionId" -> session = reader.nextString();
                case "epoch" -> epoch = reader.nextLong();
                case "stage" -> stage = reader.nextString();
                case "baseVersion" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull();
                    else base = reader.nextLong();
                }
                case "playerCount" -> players = reader.nextInt();
                case "address" -> address = reader.nextString();
                case "port" -> port = reader.nextInt();
                default -> throw new IllegalArgumentException("Unknown field");
            }
        }
        reader.endObject();
        reader.finish();
        Set<String> required = new HashSet<>();
        if (action == Action.ACQUIRE) required.addAll(Set.of("worldId", "deviceId", "requestId"));
        else {
            required.addAll(IDENTITY_FIELDS);
            if (epoch < 1) throw new IllegalArgumentException("Invalid epoch");
            WorldStore.validateId(session);
        }
        if (action == Action.READY) {
            required.add("stage");
            WorldStore.ReadyStage ready = WorldStore.ReadyStage.valueOf(Objects.requireNonNullElse(stage, ""));
            if (ready == WorldStore.ReadyStage.SAVE_SYNCED) required.add("baseVersion");
            else required.addAll(Set.of("address", "port"));
        } else if (action == Action.STOP) {
            required.add("stage");
            if (WorldStore.StopStage.valueOf(Objects.requireNonNullElse(stage, "")) == WorldStore.StopStage.UPLOAD_COMPLETE) {
                required.add("baseVersion");
            }
        }
        Set<String> allowed = new HashSet<>(required);
        if (action == Action.HEARTBEAT) allowed.add("playerCount");
        if (!fields.containsAll(required) || !allowed.containsAll(fields)) throw new IllegalArgumentException("Invalid fields");
        WorldStore.validateId(world);
        WorldStore.validateId(device);
        if (action == Action.ACQUIRE) WorldStore.validateId(request);
        if (base != null && base < 1) throw new IllegalArgumentException("Invalid base version");
        if (players != null && players < 0) throw new IllegalArgumentException("Invalid player count");
        HostEndpoint endpoint = fields.contains("address") ? new HostEndpoint(address, port) : null;
        return new Command(world, device, request, session, epoch, stage, base, players, endpoint);
    }

}
