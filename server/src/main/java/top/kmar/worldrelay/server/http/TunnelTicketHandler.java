package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.tunnel.TunnelGateway;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** API Token 只用于 API 取票；隧道握手使用短时、单次、限定用途的票据。 */
public final class TunnelTicketHandler implements HttpHandler {

    private final TunnelGateway gateway;

    public TunnelTicketHandler(TunnelGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (HttpRequests.rejectNonJsonContentType(exchange)) return;
        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) {
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "Ticket request too large");
            return;
        }
        try {
            JsonReader reader = new JsonReader(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), 4096, 2);
            Map<String, String> fields = new HashMap<>();
            reader.beginObject();
            long epoch = 0;
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (key.equals("epoch")) {
                    epoch = reader.nextLong();
                    fields.put(key, Long.toString(epoch));
                } else if (key.equals("allowNewPlayer")) fields.put(key, Boolean.toString(reader.nextBoolean()));
                else fields.put(key, reader.nextString());
            }
            reader.endObject();
            reader.finish();
            boolean host = "host".equals(fields.get("role"));
            Set<String> expected = host ? Set.of("role", "worldId", "deviceId", "sessionId", "epoch")
                : Set.of("role", "worldId", "allowNewPlayer");
            if (!fields.keySet().equals(expected) || !host && !"join".equals(fields.get("role"))) throw new IllegalArgumentException();
            String world = fields.get("worldId");
            WorldStore.validateId(world);
            var grant = host ? gateway.host(world, new WorldStore.Identity(exchange.getPrincipal().getUsername(), fields.get("deviceId"), fields.get("sessionId"), epoch))
                : gateway.join(world, exchange.getPrincipal().getUsername(), Boolean.parseBoolean(fields.get("allowNewPlayer")));
            JsonWriter writer = new JsonWriter();
            gateway.describe(writer, grant);
            HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8", writer.finish().getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_TUNNEL_REQUEST", "Invalid tunnel ticket request");
        }
    }

}
