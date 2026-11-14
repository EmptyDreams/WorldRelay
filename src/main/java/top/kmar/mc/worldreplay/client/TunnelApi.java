package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.kmar.worldrelay.tunnel.TunnelEndpoint;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/** 服务端决定传输方式和实际入口；客户端不保存额外的公网地址或端口。 */
final class TunnelApi {

    record Ticket(TunnelEndpoint endpoint, String token) {

        @Override
        public @org.jetbrains.annotations.NotNull String toString() {
            return "TunnelTicket[redacted]";
        }

    }

    private TunnelApi() {
    }

    static Ticket ticket(ClientConnectionSettings settings, String user, String world, LocalWorldSession owner) throws IOException {
        URI api = settings.serverUri();
        JsonObject request = owner == null ? new JsonObject() : WorldOwnershipClient.identity(owner);
        request.addProperty("role", owner == null ? "join" : "host");
        request.addProperty("worldId", world);
        // 兼容已有协调服务的票据格式；玩家数据按原版规则自动初始化。
        if (owner == null) request.addProperty("allowNewPlayer", true);
        try {
            JsonObject body = JsonParser.parseString(WorldOwnershipClient.post(settings, user, "tunnel-ticket", request, false)).getAsJsonObject();
            String token = ClientJson.requiredString(body, "ticket");
            if (!token.matches("[A-Za-z0-9_-]{43}"))
                throw new IllegalArgumentException("Invalid ticket");
            Instant.parse(ClientJson.requiredString(body, "expiresAt")); // 到期由服务端单调时钟裁决，不依赖客户端时钟。
            TunnelEndpoint endpoint;
            String mode = ClientJson.requiredString(body, "mode");
            if (mode.equals("wss")) {
                String path = ClientJson.requiredString(body, "path");
                if (!path.equals(top.kmar.worldrelay.tunnel.TunnelWire.PATH)) throw new IllegalArgumentException("Invalid tunnel path");
                endpoint = new TunnelEndpoint(new URI("https".equals(api.getScheme()) ? "wss" : "ws", null,
                    api.getHost(), api.getPort(), path, null, null), null);
            } else if (mode.equals("tcp")) {
                int port = body.get("port").getAsBigDecimal().intValueExact();
                if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
                endpoint = new TunnelEndpoint(new URI("tls", null, ClientJson.requiredString(body, "host"), port, null, null, null),
                    ClientJson.requiredString(body, "fingerprint"));
            } else throw new IllegalArgumentException("Unknown tunnel mode");
            return new Ticket(endpoint, token);
        } catch (RuntimeException | java.net.URISyntaxException failure) {
            throw new IOException("Invalid tunnel response");
        }
    }

    static void ready(ClientConnectionSettings settings, String user, LocalWorldSession owner, String registration) throws IOException {
        JsonObject body = WorldOwnershipClient.identity(owner);
        body.addProperty("stage", "PORT_OPENED");
        body.addProperty("tunnelId", registration);
        WorldOwnershipClient.parse(WorldOwnershipClient.post(settings, user, "ready", body, false)).checkSession(owner);
    }

}
