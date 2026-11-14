package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonParser;
import top.kmar.worldrelay.permissions.OperatorPolicy;

import java.io.IOException;
import java.util.TreeMap;

/** 仅后台调用；响应必须属于当前房主任期，不使用客户端 OP 文件或缓存。 */
final class OperatorClient {
    private OperatorClient() { }

    static OperatorPolicy fetch(ClientConnectionSettings settings, String user, LocalWorldSession session, long appliedRevision) throws IOException {
        var request = WorldOwnershipClient.identity(session);
        request.addProperty("appliedRevision", appliedRevision);
        return parse(WorldOwnershipClient.post(settings, user, "operators", request, false), session, appliedRevision);
    }

    static OperatorPolicy parse(String json, LocalWorldSession session, long appliedRevision) throws IOException {
        try {
            var body = JsonParser.parseString(json).getAsJsonObject();
            var epoch = body.getAsJsonPrimitive("epoch");
            if (!session.worldId().equals(ClientJson.requiredString(body, "worldId"))
                || !session.sessionId().equals(ClientJson.requiredString(body, "sessionId"))
                || !epoch.isNumber() || epoch.getAsBigDecimal().longValueExact() != session.epoch())
                throw new IllegalArgumentException("Operator session mismatch");
            var version = body.getAsJsonPrimitive("revision");
            if (!version.isNumber()) throw new IllegalArgumentException();
            long revision = version.getAsBigDecimal().longValueExact();
            var unchanged = body.getAsJsonPrimitive("unchanged");
            if (!unchanged.isBoolean() || revision < 0 || revision < appliedRevision) throw new IllegalArgumentException();
            if (unchanged.getAsBoolean()) {
                if (appliedRevision < 0 || revision != appliedRevision || body.has("operators")) throw new IllegalArgumentException();
                return null;
            }
            if (revision <= appliedRevision) throw new IllegalArgumentException("Policy did not advance");
            if (body.getAsJsonArray("operators").size() > OperatorPolicy.MAX_ENTRIES) throw new IllegalArgumentException();
            var entries = new TreeMap<String, Integer>();
            for (var item : body.getAsJsonArray("operators")) {
                var entry = item.getAsJsonObject();
                String name = ClientJson.requiredString(entry, "username");
                var level = entry.getAsJsonPrimitive("level");
                if (!level.isNumber() || entries.putIfAbsent(name, level.getAsBigDecimal().intValueExact()) != null)
                    throw new IllegalArgumentException();
            }
            return new OperatorPolicy(revision, entries);
        } catch (RuntimeException failure) { throw new IOException("Invalid operator policy response", failure); }
    }
}
