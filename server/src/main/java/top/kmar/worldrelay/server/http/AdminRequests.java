package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.world.WorldAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 管理请求只接受小型、字段固定的 JSON 对象。 */
final class AdminRequests {

    private AdminRequests() {
    }

    static Map<String, Object> read(HttpExchange exchange, Set<String> required, Set<String> optional)
        throws IOException, WorldAccessException {
        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) {
            exchange.getResponseHeaders().set("Connection", "close");
            throw new WorldAccessException(413, "PAYLOAD_TOO_LARGE", "Management request exceeds 4096 bytes");
        }
        String json = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        var reader = new JsonReader(json, 4096, 2);
        Map<String, Object> values = new HashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ((!required.contains(name) && !optional.contains(name)) || values.containsKey(name))
                throw new IllegalArgumentException("Unknown field");
            Object value = switch (reader.peek()) {
                case STRING -> reader.nextString();
                case NUMBER -> reader.nextLong();
                case BOOLEAN -> reader.nextBoolean();
                case NULL -> {
                    reader.nextNull();
                    yield null;
                }
                default -> throw new IllegalArgumentException("Invalid management field");
            };
            values.put(name, value);
        }
        reader.endObject();
        reader.finish();
        if (!values.keySet().containsAll(required)) throw new IllegalArgumentException("Missing field");
        return values;
    }

    static String string(Map<String, Object> values, String name) {
        if (!(values.get(name) instanceof String value)) throw new IllegalArgumentException("Expected string");
        return value;
    }

    static Long number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) return null;
        if (!(value instanceof Long number)) throw new IllegalArgumentException("Expected integer");
        return number;
    }

    static long requiredNumber(Map<String, Object> values, String name) {
        Long value = number(values, name);
        if (value == null) throw new IllegalArgumentException("Expected integer");
        return value;
    }

}