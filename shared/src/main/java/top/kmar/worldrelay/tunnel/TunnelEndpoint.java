package top.kmar.worldrelay.tunnel;

import java.net.URI;

/** TCP 校验 API 下发的证书指纹；WebSocket 沿用 API 的 HTTP/HTTPS 协议选择 WS/WSS。 */
public record TunnelEndpoint(URI uri, String fingerprint) {

    public TunnelEndpoint {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !java.util.Set.of("tls", "wss", "ws").contains(uri.getScheme())) throw new IllegalArgumentException("Invalid tunnel endpoint");
        if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("Invalid tunnel port");
        if (uri.getScheme().equals("tls") && (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Missing TLS fingerprint");
    }

    public int port() {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    public boolean webSocket() {
        return !uri.getScheme().equals("tls");
    }

    @SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused"}) // 服务端配置使用；IDEA 也单独分析客户端的共享源。
    public static boolean loopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
    }

}
