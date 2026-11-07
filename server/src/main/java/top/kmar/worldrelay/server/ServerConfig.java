package top.kmar.worldrelay.server;

import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.tunnel.TunnelConfig;
import top.kmar.worldrelay.server.world.RetentionConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** HTTP 服务配置；类初始化期间不读取文件或部署环境配置。 */
public record ServerConfig(String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds,
                           int registrationPort, Path usersFile, Path worldsDirectory,
                           int heartbeatIntervalSeconds, int heartbeatTimeoutSeconds, SshRegistrationConfig ssh, TunnelConfig tunnel,
                           RetentionConfig retention) {

    private static final Set<String> KEYS = Set.of(
        "http.bind-address", "http.port", "http.max-concurrent-requests", "http.shutdown-grace-seconds",
        "registration.port", "auth.users-file", "worlds.directory",
        "ownership.heartbeat-interval-seconds", "ownership.heartbeat-timeout-seconds",
        "ssh.authorized-keys-file", "ssh.transfer-directory", "ssh.rrsync-command", "ssh.transfer-user", "ssh.host-public-key-file",
        "ssh.public-host", "ssh.public-port", "tunnel.mode", "tunnel.bind-address", "tunnel.port",
        "tunnel.public-host", "tunnel.public-port", "tunnel.tls-directory",
        "retention.recent-versions", "retention.daily-backup-days", "retention.temporary-hours",
        "retention.minimum-free-bytes", "retention.sweep-interval-minutes", "retention.time-zone"
    );

    public ServerConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        bindAddress = bindAddress.strip();
        if (bindAddress.isEmpty()) {
            throw new IllegalArgumentException("http.bind-address must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("http.port must be between 0 and 65535");
        }
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("http.max-concurrent-requests must be positive");
        }
        if (shutdownGraceSeconds < 0) {
            throw new IllegalArgumentException("http.shutdown-grace-seconds must not be negative");
        }
        if (registrationPort < 0 || registrationPort > 65535) {
            throw new IllegalArgumentException("registration.port must be between 0 and 65535");
        }
        if (port != 0 && port == registrationPort) {
            throw new IllegalArgumentException("HTTP and registration ports must be different");
        }
        Objects.requireNonNull(usersFile, "usersFile");
        if (usersFile.toString().isBlank() || usersFile.getFileName() == null) {
            throw new IllegalArgumentException("auth.users-file must name a file");
        }
        Objects.requireNonNull(worldsDirectory, "worldsDirectory");
        if (worldsDirectory.toString().isBlank()) {
            throw new IllegalArgumentException("worlds.directory must name a directory");
        }
        if (heartbeatIntervalSeconds < 1 || heartbeatTimeoutSeconds <= heartbeatIntervalSeconds) {
            throw new IllegalArgumentException("Heartbeat timeout must be greater than the positive interval");
        }
        Objects.requireNonNull(ssh, "ssh");
        Objects.requireNonNull(tunnel, "tunnel");
        Objects.requireNonNull(retention, "retention");
        if (tunnel.port() != 0 && (tunnel.port() == port || tunnel.port() == registrationPort))
            throw new IllegalArgumentException("Tunnel port must differ from HTTP ports");
    }

    public ServerConfig(
        String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds,
        int registrationPort, Path usersFile, Path worldsDirectory, int heartbeatIntervalSeconds,
        int heartbeatTimeoutSeconds, SshRegistrationConfig ssh, TunnelConfig tunnel
    ) {
        this(bindAddress, port, maxConcurrentRequests, shutdownGraceSeconds, registrationPort, usersFile, worldsDirectory,
            heartbeatIntervalSeconds, heartbeatTimeoutSeconds, ssh, tunnel, RetentionConfig.defaults());
    }

    public ServerConfig(
        String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds,
        int registrationPort, Path usersFile, Path worldsDirectory, int heartbeatIntervalSeconds,
        int heartbeatTimeoutSeconds, SshRegistrationConfig ssh
    ) {
        this(bindAddress, port, maxConcurrentRequests, shutdownGraceSeconds, registrationPort, usersFile, worldsDirectory,
            heartbeatIntervalSeconds, heartbeatTimeoutSeconds, ssh, TunnelConfig.defaults(usersFile, port));
    }

    public ServerConfig(
        String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds,
        int registrationPort, Path usersFile, Path worldsDirectory, int heartbeatIntervalSeconds, int heartbeatTimeoutSeconds
    ) {
        this(bindAddress, port, maxConcurrentRequests, shutdownGraceSeconds, registrationPort, usersFile, worldsDirectory,
            heartbeatIntervalSeconds, heartbeatTimeoutSeconds, SshRegistrationConfig.defaults(usersFile));
    }

    public ServerConfig(
        String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds,
        int registrationPort, Path usersFile, Path worldsDirectory
    ) {
        this(bindAddress, port, maxConcurrentRequests, shutdownGraceSeconds, registrationPort, usersFile,
            worldsDirectory, 10, 30);
    }

    public ServerConfig(
        String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds,
        int registrationPort, Path usersFile
    ) {
        this(bindAddress, port, maxConcurrentRequests, shutdownGraceSeconds, registrationPort, usersFile,
            Path.of("data/worlds"));
    }

    public ServerConfig(String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds) {
        this(bindAddress, port, maxConcurrentRequests, shutdownGraceSeconds, 8081, Path.of("data/users.json"));
    }

    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", 8080, 32, 5);
    }

    public static ServerConfig fromArgs(String... args) throws IOException {
        if (args.length == 0) {
            return defaults();
        }
        if (args.length != 2 || !"--config".equals(args[0]) || args[1].isBlank()) {
            throw new IllegalArgumentException("Usage: worldrelay-server [--config <path>]");
        }
        return load(Path.of(args[1]));
    }

    public static ServerConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown configuration key: " + key);
            }
        }
        ServerConfig defaults = defaults();
        Path usersFile = Path.of(properties.getProperty("auth.users-file", defaults.usersFile().toString()));
        SshRegistrationConfig sshDefaults = SshRegistrationConfig.defaults(usersFile);
        return new ServerConfig(
            properties.getProperty("http.bind-address", defaults.bindAddress()),
            integer(properties, "http.port", defaults.port()),
            integer(properties, "http.max-concurrent-requests", defaults.maxConcurrentRequests()),
            integer(properties, "http.shutdown-grace-seconds", defaults.shutdownGraceSeconds()),
            integer(properties, "registration.port", defaults.registrationPort()),
            usersFile,
            Path.of(properties.getProperty("worlds.directory", defaults.worldsDirectory().toString())),
            integer(properties, "ownership.heartbeat-interval-seconds", defaults.heartbeatIntervalSeconds()),
            integer(properties, "ownership.heartbeat-timeout-seconds", defaults.heartbeatTimeoutSeconds()),
            new SshRegistrationConfig(
                Path.of(properties.getProperty("ssh.authorized-keys-file", sshDefaults.authorizedKeysFile().toString())),
                Path.of(properties.getProperty("ssh.transfer-directory", sshDefaults.transferDirectory().toString())),
                properties.getProperty("ssh.rrsync-command", sshDefaults.rrsyncCommand()),
                properties.getProperty("ssh.transfer-user", sshDefaults.transferUser()),
                Path.of(properties.getProperty("ssh.host-public-key-file", sshDefaults.hostPublicKeyFile().toString())),
                properties.getProperty("ssh.public-host", sshDefaults.publicHost()),
                integer(properties, "ssh.public-port", sshDefaults.publicPort())),
            TunnelConfig.load(properties, usersFile, integer(properties, "http.port", defaults.port())),
            RetentionConfig.load(properties)
        );
    }

    private static int integer(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be an integer", failure);
        }
    }

}
