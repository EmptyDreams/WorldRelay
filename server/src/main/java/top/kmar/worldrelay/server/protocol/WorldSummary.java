package top.kmar.worldrelay.server.protocol;

import top.kmar.worldrelay.server.json.JsonWriter;

/** 共享世界列表的展示摘要；lastCommittedAt 为 null 表示尚未提交，不代表已取得所有权或可以连接。 */
public record WorldSummary(
    String worldId,
    String name,
    String status,
    String hostName,
    Integer playerCount,
    String lastCommittedAt,
    HostEndpoint endpoint,
    boolean allowOfflinePlayers
) {

    /** 在已有 Writer 中写入一个完整摘要对象，由外层负责结束整个 JSON 文档。 */
    public void writeJson(JsonWriter writer) {
        writer.beginObject()
            .name("worldId").value(worldId)
            .name("name").value(name)
            .name("allowOfflinePlayers").value(allowOfflinePlayers)
            .name("status").value(status)
            .name("hostName");
        if (hostName == null) {
            writer.nullValue();
        } else {
            writer.value(hostName);
        }
        writer.name("playerCount");
        if (playerCount == null) {
            writer.nullValue();
        } else {
            writer.value(playerCount);
        }
        writer.name("lastCommittedAt");
        if (lastCommittedAt == null) {
            writer.nullValue();
        } else {
            writer.value(lastCommittedAt);
        }
        writer.name("endpoint");
        if (endpoint == null) {
            writer.nullValue();
        } else {
            endpoint.writeJson(writer);
        }
        writer.endObject();
    }

}
