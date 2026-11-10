package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.json.JsonWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 管理侧只读快照及有界历史查询，不对玩家接口公开会话身份。 */
public final class WorldAdministration {

    private final WorldStore worlds;

    public WorldAdministration(WorldStore worlds) {
        this.worlds = worlds;
    }

    public String list() {
        var writer = new JsonWriter().beginObject().name("worlds").beginArray();
        for (var state : worlds.adminStates()) {
            write(writer, state);
        }
        return writer.endArray().endObject().finish();
    }

    public void detail(JsonWriter writer, String worldId) throws WorldAccessException {
        write(writer, worlds.adminState(worldId));
    }

    private static void write(JsonWriter writer, WorldStore.AdminState entry) {
        var metadata = entry.metadata();
        var state = metadata.state();
        writer.beginObject().name("worldId").value(metadata.worldId()).name("name").value(metadata.name())
            .name("createdAt").value(metadata.createdAt()).name("phase").value(state.phase().name())
            .name("status").value(entry.status()).name("epoch").value(state.epoch())
            .name("online").value(entry.online()).name("unavailable").value(entry.unavailable())
            .name("restoring").value(entry.restoring());
        number(writer, "committedVersion", state.committedVersion());
        number(writer, "baseVersion", state.baseVersion());
        text(writer, "lastCommittedAt", state.lastCommittedAt());
        text(writer, "lastContactAt", entry.lastContactAt() == null ? null : entry.lastContactAt().toString());
        text(writer, "previousOwner", events(metadata).stream().filter(event -> !event.type().equals("RESTORE"))
            .max(Comparator.comparingLong(Event::epoch)).map(event -> event.owner().username()).orElse(null));
        number(writer, "playerCount", entry.players() == null ? null : entry.players().longValue());
        writer.name("owner");
        if (state.owner() == null) writer.nullValue();
        else {
            var owner = state.owner();
            writer.beginObject().name("username").value(owner.username()).name("deviceId").value(owner.deviceId())
                .name("sessionId").value(owner.sessionId()).name("requestId").value(owner.requestId()).endObject();
        }
        writer.name("endpoint");
        if (state.endpoint() == null) writer.nullValue();
        else state.endpoint().writeJson(writer);
        writer.endObject();
    }

    private record Event(String type, long epoch, String at, WorldMetadata.Owner owner, String operationId,
                         Long version, Long sourceVersion, String reason) {

    }

    public String history(String worldId, Long beforeEpoch, int limit) throws WorldAccessException {
        if (limit < 1 || limit > 200 || beforeEpoch != null && beforeEpoch < 1) throw new IllegalArgumentException("Invalid history page");
        var metadata = worlds.adminState(worldId).metadata();
        var selected = events(metadata).stream().filter(event -> beforeEpoch == null || event.epoch < beforeEpoch)
            .sorted(Comparator.comparingLong(Event::epoch).reversed()).limit(limit + 1L).toList();
        boolean more = selected.size() > limit;
        var page = selected.subList(0, Math.min(limit, selected.size()));
        var writer = new JsonWriter().beginObject().name("worldId").value(worldId).name("retainedOnly").value(true)
            .name("hasMore").value(more);
        number(writer, "nextBeforeEpoch", more ? page.getLast().epoch : null);
        writer.name("events").beginArray();
        for (var event : page) {
            writer.beginObject().name("type").value(event.type).name("epoch").value(event.epoch);
            text(writer, "at", event.at);
            writer.name("username").value(event.owner.username()).name("deviceId").value(event.owner.deviceId())
                .name("sessionId").value(event.owner.sessionId()).name("requestId").value(event.owner.requestId());
            text(writer, "operationId", event.operationId);
            number(writer, "version", event.version);
            number(writer, "sourceVersion", event.sourceVersion);
            text(writer, "reason", event.reason);
            writer.endObject();
        }
        return writer.endArray().endObject().finish();
    }

    private static List<Event> events(WorldMetadata metadata) {
        var events = new ArrayList<Event>();
        for (var commit : metadata.commits()) {
            events.add(new Event(commit.restoredFromVersion() == null ? "COMMIT" : "RESTORE", commit.epoch(),
                commit.committedAt(), commit.owner(), commit.uploadId(), commit.version(), commit.restoredFromVersion(), null));
        }
        for (var cancellation : metadata.cancellations()) {
            events.add(new Event(cancellation.abandonId() == null ? "CANCEL_CREATION" : "ABANDON", cancellation.epoch(),
                cancellation.cancelledAt(), cancellation.owner(), cancellation.abandonId(), cancellation.committedVersion(), null, cancellation.reason()));
        }
        return events;
    }

    private static void text(JsonWriter writer, String name, String value) {
        writer.name(name);
        if (value == null) writer.nullValue();
        else writer.value(value);
    }

    private static void number(JsonWriter writer, String name, Long value) {
        writer.name(name);
        if (value == null) writer.nullValue();
        else writer.value(value);
    }

}
