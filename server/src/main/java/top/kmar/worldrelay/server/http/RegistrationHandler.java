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
        if (HttpRequests.rejectNonJsonContentType(exchange)) return;
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            exchange.getResponseHeaders().set("Connection", "close");
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "Registration body exceeds 4096 bytes");
            return;
        }
        String username = null;
        boolean overwrite = false;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            JsonReader reader = new JsonReader(json, MAX_BODY_BYTES, 4);
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "username" -> username = reader.nextString();
                    case "overwrite" -> overwrite = reader.nextBoolean();
                    default -> throw new IllegalArgumentException("Unknown registration field");
                }
            }
            reader.endObject();
            reader.finish();
            UserStore.validateUsername(username);
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_REGISTRATION", "Expected a valid username");
            return;
        }
        UserStore.Registration registration;
        try {
            registration = users.register(username, overwrite);
        } catch (UserStore.AuthorizationConflictException conflict) {
            HttpResponses.error(exchange, 409, "AUTHORIZED_KEYS_CONFLICT", "Username has an unmanaged or ambiguous SSH authorization");
            return;
        }
        if (registration == null) {
            HttpResponses.error(exchange, 409, "USERNAME_EXISTS", "Username already registered");
            return;
        }
        String json = new JsonWriter().beginObject()
            .name("username").value(username).name("key").value(registration.key())
            .name("publicKey").value(registration.publicKey()).name("privateKey").value(registration.privateKey())
            .endObject().finish();
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        HttpResponses.bytes(exchange, registration.overwritten() ? 200 : 201,
            "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

}
