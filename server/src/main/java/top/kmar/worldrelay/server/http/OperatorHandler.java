package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.auth.OperatorStore;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 写入仅注册到本机管理监听器；房主同步端点只读并校验当前任期。 */
public final class OperatorHandler implements HttpHandler {
    public enum Action { LIST, SET, REMOVE, SYNC }
    private final OperatorStore operators;
    private final UserStore users;
    private final WorldStore worlds;
    private final Action action;

    public OperatorHandler(OperatorStore operators, UserStore users, WorldStore worlds, Action action) {
        this.operators = operators;
        this.users = users;
        this.worlds = worlds;
        this.action = action;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (action != Action.LIST && HttpRequests.rejectNonJsonContentType(exchange)) return;
        try {
            var writer = new JsonWriter().beginObject();
            if (action == Action.SYNC) {
                var body = AdminRequests.read(exchange, Set.of("worldId", "deviceId", "sessionId", "epoch", "appliedRevision"), Set.of());
                String world = AdminRequests.string(body, "worldId");
                var owner = new WorldStore.Identity(exchange.getPrincipal().getUsername(), AdminRequests.string(body, "deviceId"),
                    AdminRequests.string(body, "sessionId"), AdminRequests.requiredNumber(body, "epoch"));
                worlds.checkTunnelOwner(world, owner);
                long applied = AdminRequests.requiredNumber(body, "appliedRevision");
                var policy = operators.snapshot();
                if (applied < -1 || applied > policy.revision()) throw new IllegalArgumentException("Invalid applied revision");
                writer.name("worldId").value(world).name("sessionId").value(owner.sessionId()).name("epoch").value(owner.epoch());
                if (applied == policy.revision()) writer.name("revision").value(policy.revision()).name("unchanged").value(true);
                else {
                    writer.name("unchanged").value(false);
                    OperatorStore.write(writer, policy);
                }
            } else {
                var policy = operators.snapshot();
                if (action != Action.LIST) {
                    var body = AdminRequests.read(exchange, action == Action.SET ? Set.of("username", "level", "expectedRevision")
                        : Set.of("username", "expectedRevision"), Set.of());
                    String username = AdminRequests.string(body, "username");
                    if (users.list().stream().noneMatch(account -> account.username().equals(username)))
                        throw new WorldAccessException(404, "USER_NOT_FOUND", "User not found");
                    int level = action == Action.REMOVE ? 0 : Math.toIntExact(AdminRequests.requiredNumber(body, "level"));
                    if (action == Action.SET && level < 1) throw new IllegalArgumentException("Invalid level");
                    policy = operators.update(AdminRequests.requiredNumber(body, "expectedRevision"), username, level);
                }
                OperatorStore.write(writer, policy);
            }
            HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8", writer.endObject().finish().getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IllegalArgumentException | ArithmeticException | java.nio.charset.CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_OPERATOR_REQUEST", "Invalid operator request");
        } catch (IOException failure) {
            HttpResponses.error(exchange, 503, "OPERATOR_STORE_UNAVAILABLE", "Operator state requires retry or recovery");
        }
    }
}
