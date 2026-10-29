package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** 只装配在独立的本地注册监听器上。 */
public final class RegistrationHandler implements HttpHandler {

    private static final int MAX_BODY_BYTES = 4096;
    private final UserStore users;

    public RegistrationHandler(UserStore users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        List<String> contentTypes = exchange.getRequestHeaders().get("Content-Type");
        if (contentTypes == null || contentTypes.size() != 1
            || !contentTypes.getFirst().split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            HttpResponses.error(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "Expected application/json in UTF-8");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            exchange.getResponseHeaders().set("Connection", "close");
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "Registration body exceeds 4096 bytes");
            return;
        }
        String username;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            JsonReader reader = new JsonReader(json, MAX_BODY_BYTES, 4);
            reader.beginObject();
            if (!reader.nextName().equals("username")) {
                throw new IllegalArgumentException("Expected username");
            }
            username = reader.nextString();
            reader.endObject();
            reader.finish();
            UserStore.validateUsername(username);
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_REGISTRATION", "Expected a valid username");
            return;
        }
        String key = users.register(username);
        if (key == null) {
            HttpResponses.error(exchange, 409, "USERNAME_EXISTS", "Username already registered");
            return;
        }
        String json = new JsonWriter().beginObject()
            .name("username").value(username).name("key").value(key).endObject().finish();
        HttpResponses.bytes(exchange, 201, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

}