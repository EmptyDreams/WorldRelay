package top.kmar.worldrelay.server.protocol;

import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldPhase;

/** 仅用于已验证持有关系的操作响应；不能写入公共世界列表。 */
public record OwnershipView(String worldId, long epoch, String sessionId, WorldPhase phase, String status,
                            Long baseVersion, Long committedVersion, HostEndpoint endpoint,
                            int heartbeatIntervalSeconds, int heartbeatTimeoutSeconds, boolean allowOfflinePlayers) {

    public void writeJson(JsonWriter writer) {
        writer.beginObject().name("worldId").value(worldId).name("epoch").value(epoch)
            .name("sessionId").value(sessionId).name("phase").value(phase.name()).name("status").value(status)
            .name("allowOfflinePlayers").value(allowOfflinePlayers)
            .name("hasSave").value(committedVersion != null).name("baseVersion");
        if (baseVersion == null) writer.nullValue();
        else writer.value(baseVersion);
        writer.name("committedVersion");
        if (committedVersion == null) writer.nullValue();
        else writer.value(committedVersion);
        writer.name("endpoint");
        if (endpoint == null) writer.nullValue();
        else endpoint.writeJson(writer);
        writer.name("heartbeatIntervalSeconds").value(heartbeatIntervalSeconds)
            .name("heartbeatTimeoutSeconds").value(heartbeatTimeoutSeconds).endObject();
    }

}
