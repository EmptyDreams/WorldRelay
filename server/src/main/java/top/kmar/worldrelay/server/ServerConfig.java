package top.kmar.worldrelay.server;

import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.tunnel.TunnelConfig;
import top.kmar.worldrelay.server.world.RetentionConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** HTTP 服务配置；类初始化期间不读取文件或部署环境配置。 */
public record ServerConfig(String bindAddress, int port, int maxConcurrentRequests, int shutdownGraceSeconds,
                           int registrationPort, Path usersFile, Path worldsDirectory,
                           int heartbeatIntervalSeconds, int heartbeatTimeoutSeconds, SshRegistrationConfig ssh, TunnelConfig tunnel,
                           RetentionConfig retention) {

    static final Path CONFIG_FILE = Path.of("server.properties");

    private record Option(String key, String comment, Function<ServerConfig, ?> defaultValue) {
    }

    private static final List<Option> OPTIONS = List.of(
        new Option("http.bind-address", "玩家接口监听地址；默认只允许本机访问，通常由反向代理提供公网入口。", ServerConfig::bindAddress),
        new Option("http.port", "玩家接口端口；0 表示由系统分配可用端口。", ServerConfig::port),
        new Option("http.max-concurrent-requests", "每个 HTTP 监听器同时处理的请求上限，至少为 1。", ServerConfig::maxConcurrentRequests),
        new Option("http.shutdown-grace-seconds", "停止服务时等待正在处理的 HTTP 请求结束的秒数。", ServerConfig::shutdownGraceSeconds),
        new Option("registration.port", "本机管理端口，固定监听 127.0.0.1；管理终端需使用相同端口，请勿开放到公网。", ServerConfig::registrationPort),
        new Option("ownership.heartbeat-interval-seconds", "房主发送心跳的建议间隔，单位秒，至少为 1。", ServerConfig::heartbeatIntervalSeconds),
        new Option("ownership.heartbeat-timeout-seconds", "判定房主离线的秒数，须大于心跳间隔；离线不会自动允许他人接管。", ServerConfig::heartbeatTimeoutSeconds),
        new Option("ssh.rrsync-command", "rrsync 程序的完整路径，按服务器实际安装位置填写。", config -> config.ssh().rrsyncCommand()),
        new Option("ssh.transfer-user", "SSH 存档传输使用的 Linux 账户，默认使用生成配置时的服务账户。", config -> config.ssh().transferUser()),
        new Option("ssh.host-public-key-file", "SSH 服务的 Ed25519 主机公钥文件；填写 .pub 公钥文件的路径。", config -> config.ssh().hostPublicKeyFile()),
        new Option("ssh.public-host", "玩家可访问的 SSH 域名或 IP；部署时修改为实际地址。", config -> config.ssh().publicHost()),
        new Option("ssh.public-port", "玩家可访问的 SSH 端口；有端口映射时填写映射后的端口。", config -> config.ssh().publicPort()),
        new Option("tunnel.mode", "游戏连接方式，只能选择一种：wss 使用 HTTP(S) 站点的 WebSocket 入口，tcp 使用独立 TCP 端口。", config -> config.tunnel().mode().name().toLowerCase(java.util.Locale.ROOT)),
        new Option("tunnel.bind-address", "游戏连接监听地址；wss 必须使用回环地址，tcp 对外监听时可填 0.0.0.0。", config -> config.tunnel().bindAddress()),
        new Option("tunnel.port", "游戏连接监听端口，不能与玩家接口或管理端口重复；0 表示自动分配。", config -> config.tunnel().port()),
        new Option("tunnel.public-host", "仅 tcp 使用，填写玩家可访问的域名或 IP；wss 留空并沿用玩家接口地址。", config -> config.tunnel().publicHost()),
        new Option("tunnel.public-port", "仅 tcp 使用，填写玩家可访问的端口；0 表示使用实际监听端口。", config -> config.tunnel().port()),
        new Option("retention.recent-versions", "每个世界保留的最近版本数，至少为 1。", config -> config.retention().recentVersions()),
        new Option("retention.daily-backup-days", "每天保留最后一个版本的天数；0 关闭每日备份保留。", config -> config.retention().dailyBackupDays()),
        new Option("retention.temporary-hours", "临时文件及删除回收副本保留的小时数，至少为 1；正在使用的数据受保护。", config -> config.retention().temporaryHours()),
        new Option("retention.minimum-free-bytes", "磁盘可用空间低于此字节数时，优先清理最早的历史备份；默认 1 GiB。", config -> config.retention().minimumFreeBytes()),
        new Option("retention.sweep-interval-minutes", "自动清理的检查间隔，单位分钟，至少为 1。", config -> config.retention().sweepIntervalMinutes()),
        new Option("retention.time-zone", "每日备份按此时区划分日期，默认使用生成配置时的系统时区，例如 Asia/Shanghai。", config -> config.retention().timeZone().getId())
    );
    private static final Set<String> KEYS = OPTIONS.stream().map(Option::key).collect(Collectors.toUnmodifiableSet());

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
            throw new IllegalArgumentException("User storage must name a file");
        }
        Objects.requireNonNull(worldsDirectory, "worldsDirectory");
        if (worldsDirectory.toString().isBlank()) {
            throw new IllegalArgumentException("World storage must name a directory");
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
        if (args.length != 0) throw new IllegalArgumentException("Usage: worldrelay-server [--config]");
        if (Files.notExists(CONFIG_FILE))
            throw new IOException("Missing server.properties; run worldrelay-server --config to initialize it");
        return load(CONFIG_FILE);
    }

    /** 从同一份配置项定义生成完整说明，已有文件保持原样。 */
    static boolean createDefaultFile(Path path) throws IOException {
        ServerConfig defaults = defaults();
        StringBuilder text = new StringBuilder("""
            # WorldRelay 服务端配置（UTF-8），修改后重启服务生效。
            # 存档、传输文件和程序数据固定保存在启动目录的 data/ 内。
            # SSH 传输公钥固定使用传输账户的 ~/.ssh/authorized_keys。

            """);
        for (Option option : OPTIONS) {
            String value = option.defaultValue().apply(defaults).toString().replace("\\", "\\\\")
                .replace("\r", "\\r").replace("\n", "\\n");
            text.append("# ").append(option.comment()).append('\n')
                .append(option.key()).append('=').append(value).append("\n\n");
        }
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException existing) {
            return false;
        }
    }

    /** 初始化程序目录；服务启动和仅生成配置的命令共用，均不要求用户手动创建目录。 */
    void createDirectories() throws IOException {
        for (Path directory : List.of(usersFile.toAbsolutePath().getParent(), worldsDirectory,
            ssh.transferDirectory(), tunnel.tlsDirectory())) {
            Files.createDirectories(directory);
        }
        Path sshDirectory = ssh.authorizedKeysFile().toAbsolutePath().getParent();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(sshDirectory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(sshDirectory);
        }
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
        Path usersFile = defaults.usersFile();
        SshRegistrationConfig sshDefaults = defaults.ssh();
        String transferUser = properties.getProperty("ssh.transfer-user", sshDefaults.transferUser());
        return new ServerConfig(
            properties.getProperty("http.bind-address", defaults.bindAddress()),
            integer(properties, "http.port", defaults.port()),
            integer(properties, "http.max-concurrent-requests", defaults.maxConcurrentRequests()),
            integer(properties, "http.shutdown-grace-seconds", defaults.shutdownGraceSeconds()),
            integer(properties, "registration.port", defaults.registrationPort()),
            usersFile,
            defaults.worldsDirectory(),
            integer(properties, "ownership.heartbeat-interval-seconds", defaults.heartbeatIntervalSeconds()),
            integer(properties, "ownership.heartbeat-timeout-seconds", defaults.heartbeatTimeoutSeconds()),
            new SshRegistrationConfig(
                SshRegistrationConfig.authorizedKeysFile(transferUser),
                sshDefaults.transferDirectory(),
                properties.getProperty("ssh.rrsync-command", sshDefaults.rrsyncCommand()),
                transferUser,
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
