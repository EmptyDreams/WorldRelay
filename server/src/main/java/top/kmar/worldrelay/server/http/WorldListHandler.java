package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.protocol.WorldSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 返回固定的共享世界示例；尚未接入真实存档、在线状态或权限数据。 */
public final class WorldListHandler implements HttpHandler {

    private static final List<WorldSummary> MOCK_WORLDS = List.of(
        new WorldSummary("demo-survival", "我们的生存世界", "RUNNING", "小明", 2,
            "2026-10-28T10:40:00Z"),
        new WorldSummary("demo-building", "建筑练习场", "FREE", null, 0,
            "2026-10-27T13:16:00Z"),
        new WorldSummary("demo-machinery", "机械与铁路", "RECOVERY_REQUIRED", "小雨", null,
            "2026-10-28T09:22:00Z"),
        new WorldSummary("demo-adventure", "朋友的冒险", "COMMITTING", "阿木", null,
            "2026-10-28T08:05:00Z")
    );

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        JsonWriter writer = new JsonWriter().beginObject()
            .name("mock").value(true)
            .name("worlds").beginArray();
        for (WorldSummary world : MOCK_WORLDS) {
            world.writeJson(writer);
        }
        String body = writer.endArray().endObject().finish();
        HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8",
            body.getBytes(StandardCharsets.UTF_8));
    }

}