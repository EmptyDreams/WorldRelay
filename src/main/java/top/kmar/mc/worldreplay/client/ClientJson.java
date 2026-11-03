package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;

/** 客户端协议和恢复记录共用的严格字段读取，不在异常中回显响应内容。 */
final class ClientJson {
    private ClientJson() { }

    static String requiredString(JsonObject object, String key) {
        var value = object.getAsJsonPrimitive(key);
        if (!value.isString() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException();
        }
        return value.getAsString();
    }
}
