package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 仅装配在本机管理监听器上；登记世界，不生成 Minecraft 存档。 */
public final class WorldCreateHandler implements HttpHandler {

    private static final int MAX_BODY_BYTES = 4096;
    private final WorldStore worlds;

    public WorldCreateHandler(WorldStore worlds) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpRequests.rejectNonJsonContentType(exchange)) return;
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            exchange.getResponseHeaders().set("Connection", "close");
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "World creation body exceeds 4096 bytes");
            return;
        }
        String name;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            JsonReader reader = new JsonReader(json, MAX_BODY_BYTES, 4);
            reader.beginObject();
            if (!reader.nextName().equals("name")) {
                throw new IllegalArgumentException("Expected name");
            }
            name = reader.nextString();
            reader.endObject();
            reader.finish();
            WorldStore.validateName(name);
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_WORLD", "Expected a valid world name");
            return;
        }
        var world = worlds.create(name);
        if (world == null) {
            HttpResponses.error(exchange, 409, "WORLD_NAME_EXISTS", "World name already exists");
            return;
        }
        JsonWriter writer = new JsonWriter();
        world.writeJson(writer);
        HttpResponses.bytes(exchange, 201, "application/json; charset=utf-8",
            writer.finish().getBytes(StandardCharsets.UTF_8));
    }

}
