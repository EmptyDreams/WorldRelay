package top.kmar.worldrelay.server.tunnel;

import top.kmar.worldrelay.server.protocol.HostEndpoint;
import top.kmar.worldrelay.tunnel.TunnelEndpoint;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** 两种入口互斥；WSS 的公网地址沿用客户端已经验证的 API 来源。 */
public record TunnelConfig(Mode mode, String bindAddress, int port, String publicHost, int publicPort, Path tlsDirectory) {

    public enum Mode {WSS, TCP}

    public TunnelConfig {
        if (mode == null || bindAddress == null || bindAddress.isBlank() || port < 0 || port > 65535
            || publicPort < 0 || publicPort > 65535 || tlsDirectory == null) throw new IllegalArgumentException("Invalid tunnel configuration");
        if (mode == Mode.WSS && !TunnelEndpoint.loopback(bindAddress)) throw new IllegalArgumentException("WSS backend must bind to loopback");
        if (mode == Mode.TCP) new HostEndpoint(publicHost, publicPort == 0 ? 1 : publicPort);
    }

    public static TunnelConfig defaults(Path usersFile, int apiPort) {
        Path parent = usersFile.toAbsolutePath().getParent();
        return new TunnelConfig(Mode.WSS, "127.0.0.1", apiPort == 0 ? 0 : 2002, "", 0, parent.resolve("tunnel/tls"));
    }

    public static TunnelConfig load(Properties properties, Path usersFile, int apiPort) {
        Mode mode = Mode.valueOf(properties.getProperty("tunnel.mode", "wss").strip().toUpperCase(Locale.ROOT));
        var defaults = defaults(usersFile, apiPort);
        int port = Integer.parseInt(properties.getProperty("tunnel.port", Integer.toString(defaults.port())).strip());
        return new TunnelConfig(mode, properties.getProperty("tunnel.bind-address", mode == Mode.WSS ? "127.0.0.1" : "0.0.0.0").strip(),
            port, properties.getProperty("tunnel.public-host", "").strip(),
            Integer.parseInt(properties.getProperty("tunnel.public-port", Integer.toString(port)).strip()),
            defaults.tlsDirectory());
    }

}
