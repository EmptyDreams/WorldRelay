package top.kmar.worldrelay.tunnel;

import java.net.URI;

/** TCP 的 TLS 信任由 HTTPS API 下发的证书指纹建立；WSS 使用正常的 HTTPS 证书验证。 */
public record TunnelEndpoint(URI uri, String fingerprint) {

    public TunnelEndpoint {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !java.util.Set.of("tls", "wss", "ws").contains(uri.getScheme())) throw new IllegalArgumentException("Invalid tunnel endpoint");
        if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("Invalid tunnel port");
        if (uri.getScheme().equals("tls") && (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Missing TLS fingerprint");
        if (uri.getScheme().equals("ws") && !loopback(uri.getHost()))
            throw new IllegalArgumentException("Plain WebSocket is only allowed on loopback");
    }

    public int port() {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    public boolean webSocket() {
        return !uri.getScheme().equals("tls");
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted") // 保留正向的地址分类谓词供两端使用。
    public static boolean loopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
    }

}