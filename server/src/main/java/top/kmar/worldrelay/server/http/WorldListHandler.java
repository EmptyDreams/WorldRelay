package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.protocol.WorldSummary;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 返回已持久化的世界记录；登记记录不代表已实现托管或所有权。 */
public final class WorldListHandler implements HttpHandler {

    private final WorldStore worlds;

    public WorldListHandler(WorldStore worlds) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        JsonWriter writer = new JsonWriter().beginObject()
            .name("mock").value(false)
            .name("worlds").beginArray();
        for (WorldSummary world : worlds.list()) {
            world.writeJson(writer);
        }
        String body = writer.endArray().endObject().finish();
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8",
            body.getBytes(StandardCharsets.UTF_8));
    }

}
