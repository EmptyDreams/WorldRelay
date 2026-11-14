package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.auth.OperatorStore;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.tunnel.TunnelGateway;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** 账号状态只作用于访问资格，不自动撤销世界所有权。 */
public final class UserManagementHandler implements HttpHandler {

    public enum Action {LIST, ENABLE, DISABLE}

    private final UserStore users;
    private final TunnelGateway tunnels;
    private final TransferJobs jobs;
    private final WorldStore worlds;
    private final Action action;
    private final OperatorStore operators;

    public UserManagementHandler(UserStore users, WorldStore worlds, TunnelGateway tunnels, TransferJobs jobs, Action action, OperatorStore operators) {
        this.users = users;
        this.worlds = worlds;
        this.tunnels = tunnels;
        this.jobs = jobs;
        this.action = action;
        this.operators = Objects.requireNonNull(operators);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (action != Action.LIST && HttpRequests.rejectNonJsonContentType(exchange)) return;
        try {
            var writer = new JsonWriter();
            if (action == Action.LIST) {
                var policy = operators.snapshot();
                writer.beginObject().name("users").beginArray();
                for (var account : users.list()) {
                    writer.beginObject().name("username").value(account.username()).name("enabled").value(account.enabled())
                        .name("transferAuthorizations").value(account.transferAuthorizations());
                    writer.name("opLevel").value(policy.operators().getOrDefault(account.username(), 0));
                    writer.endObject();
                }
                writer.endArray();
                writer.name("operatorRevision").value(policy.revision());
                writer.endObject();
            } else {
                var body = AdminRequests.read(exchange, Set.of("username"), Set.of());
                String username = AdminRequests.string(body, "username");
                UserStore.Account account;
                // 与所有权变更及快照发布串行化，锁顺序统一为 WorldStore -> UserStore。
                synchronized (worlds) {
                    account = users.setEnabled(username, action == Action.ENABLE);
                    if (account == null) throw new WorldAccessException(404, "USER_NOT_FOUND", "User not found");
                    if (action == Action.DISABLE) {
                        jobs.invalidateUser(username);
                        tunnels.revokeUser(username);
                    }
                }
                write(writer, account);
                System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                    "User access updated: username={0}, enabled={1}", username, account.enabled());
            }
            HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8", writer.finish().getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_MANAGEMENT_REQUEST", "Invalid user management request");
        } catch (IOException failure) {
            HttpResponses.error(exchange, 503, "USER_MANAGEMENT_FAILED", "User state requires retry or server recovery");
        }
    }

    private static void write(JsonWriter writer, UserStore.Account account) {
        writer.beginObject().name("username").value(account.username()).name("enabled").value(account.enabled())
            .name("transferAuthorizations").value(account.transferAuthorizations()).endObject();
    }

}
