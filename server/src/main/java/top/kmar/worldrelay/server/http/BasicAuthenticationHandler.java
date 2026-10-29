package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.auth.UserStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** 对整个业务监听器鉴权，不提供基于路径或方法的免鉴权例外。 */
public final class BasicAuthenticationHandler implements HttpHandler {

    public static final String USERNAME_ATTRIBUTE = "worldrelay.username";
    public static final String CHALLENGE = "Basic realm=\"WorldRelay\", charset=\"UTF-8\"";

    private final UserStore users;
    private final HttpHandler next;

    public BasicAuthenticationHandler(UserStore users, HttpHandler next) {
        this.users = Objects.requireNonNull(users, "users");
        this.next = Objects.requireNonNull(next, "next");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String username = authenticate(exchange.getRequestHeaders().get("Authorization"));
        if (username == null) {
            exchange.getResponseHeaders().set("WWW-Authenticate", CHALLENGE);
            HttpResponses.error(exchange, 401, "UNAUTHORIZED", "Authentication required");
            return;
        }
        next.handle(new AuthenticatedExchange(exchange, username));
    }

    private String authenticate(List<String> headers) {
        if (headers == null || headers.size() != 1) {
            return null;
        }
        String header = headers.getFirst();
        if (header.length() > 1024 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String credentials;
        try {
            byte[] decoded = Base64.getDecoder().decode(header.substring(6).stripLeading());
            credentials = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decoded)).toString();
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            return null;
        }
        int separator = credentials.indexOf(':');
        if (separator < 1) {
            return null;
        }
        String username = credentials.substring(0, separator);
        String key = credentials.substring(separator + 1);
        if (!key.matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        return users.verify(username, key) ? username : null;
    }

}