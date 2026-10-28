package top.kmar.worldrelay.server.protocol;

import top.kmar.worldrelay.server.json.JsonWriter;

/** 共享世界列表的展示摘要，不代表已经取得所有权或可以直接连接。 */
public record WorldSummary(
    String worldId,
    String name,
    String status,
    String hostName,
    Integer playerCount,
    String lastCommittedAt
) {

    /** 在已有 Writer 中写入一个完整摘要对象，由外层负责结束整个 JSON 文档。 */
    public void writeJson(JsonWriter writer) {
        writer.beginObject()
            .name("worldId").value(worldId)
            .name("name").value(name)
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
        writer.name("lastCommittedAt").value(lastCommittedAt).endObject();
    }

}