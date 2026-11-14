package top.kmar.worldrelay.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @TempDir
    Path directory;

    /** 保留策略有可用默认值，配置可覆盖数量、时区和空间阈值，非法策略不能启动。 */
    @Test
    void loadsRetentionPolicyAndRejectsInvalidLimits() throws Exception {
        var defaults = ServerConfig.defaults().retention();
        assertEquals(5, defaults.recentVersions());
        assertEquals(7, defaults.dailyBackupDays());
        assertEquals(24, defaults.temporaryHours());
        assertEquals(1073741824L, defaults.minimumFreeBytes());
        Path file = directory.resolve("retention.properties");
        Files.writeString(file, """
            retention.recent-versions=3
            retention.daily-backup-days=14
            retention.temporary-hours=48
            retention.minimum-free-bytes=2147483648
            retention.sweep-interval-minutes=15
            retention.time-zone=Asia/Shanghai
            """);
        var loaded = ServerConfig.load(file).retention();
        assertEquals(new top.kmar.worldrelay.server.world.RetentionConfig(3, 14, 48, 2147483648L, 15,
            java.time.ZoneId.of("Asia/Shanghai")), loaded);
        for (String invalid : new String[]{"retention.recent-versions=0", "retention.daily-backup-days=-1",
            "retention.temporary-hours=0", "retention.minimum-free-bytes=-1", "retention.sweep-interval-minutes=0",
            "retention.time-zone=invalid", "retention.recent-versions=NaN"}) {
            Files.writeString(file, invalid);
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
        }
    }

    /** 验证配置文件选择唯一入口：WSS 仅监听回环，TCP 接受 IP 或域名，证书固定保存在程序目录中。 */
    @Test
    void selectsTunnelModeAndAdvertisesConfiguredTcpEndpoint() throws Exception {
        Path file = directory.resolve("tunnel.properties");
        Files.writeString(file, "http.port=0\nregistration.port=0\ntunnel.mode=wss\ntunnel.port=0\n");
        var wss = ServerConfig.load(file).tunnel();
        assertEquals(top.kmar.worldrelay.server.tunnel.TunnelConfig.Mode.WSS, wss.mode());
        assertEquals("127.0.0.1", wss.bindAddress());
        for (String host : new String[]{"121.89.94.223", "relay.example.com", "2001:db8::1"}) {
            Files.writeString(file, "tunnel.mode=tcp\ntunnel.port=2002\ntunnel.public-host=" + host + "\ntunnel.public-port=3002\n");
            var tcp = ServerConfig.load(file).tunnel();
            assertEquals(top.kmar.worldrelay.server.tunnel.TunnelConfig.Mode.TCP, tcp.mode());
            assertEquals("0.0.0.0", tcp.bindAddress());
            assertEquals(host, tcp.publicHost());
            assertEquals(3002, tcp.publicPort());
            assertEquals(Path.of("data/tunnel/tls").toAbsolutePath(), tcp.tlsDirectory());
        }
        Files.writeString(file, "tunnel.mode=wss\ntunnel.bind-address=0.0.0.0\n");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
    }

    /** 验证公钥使用当前账户的 SSH 目录，程序数据位置固定，工具和公网地址支持配置并校验。 */
    @Test
    void loadsSshRegistrationPathsAndRejectsEmptyValues() throws Exception {
        Path file = directory.resolve("ssh.properties");
        Files.writeString(file, "");
        var defaults = ServerConfig.load(file).ssh();
        Path keys = Path.of(System.getProperty("user.home"), ".ssh", "authorized_keys");
        Path transfers = Path.of("data/transfers");
        assertEquals(keys, defaults.authorizedKeysFile());
        assertEquals(transfers, defaults.transferDirectory());
        Files.writeString(file, """
            ssh.rrsync-command=/usr/local/bin/rrsync
            ssh.public-host=[2001:db8::1]
            ssh.public-port=2222
            """);
        var ssh = ServerConfig.load(file).ssh();
        assertEquals(keys, ssh.authorizedKeysFile());
        assertEquals(transfers, ssh.transferDirectory());
        assertEquals("/usr/local/bin/rrsync", ssh.rrsyncCommand());
        assertEquals("2001:db8::1", ssh.publicHost());
        assertEquals(2222, ssh.publicPort());
        for (String key : new String[]{"ssh.rrsync-command", "ssh.public-host", "ssh.transfer-user"}) {
            Files.writeString(file, key + "=\n");
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
        }
    }

    /** 验证指定其他 Linux 传输账户时，公钥使用该账户的真实主目录，而非服务进程的主目录。 */
    @Test
    @EnabledOnOs(OS.LINUX)
    void resolvesAuthorizedKeysInConfiguredTransferAccountHome() throws Exception {
        String[] account;
        try (var lines = Files.lines(Path.of("/etc/passwd"))) {
            account = lines.map(line -> line.split(":", -1))
                .filter(fields -> fields.length == 7 && !fields[0].equals(System.getProperty("user.name")))
                .filter(fields -> !fields[5].isBlank() && Path.of(fields[5]).isAbsolute()
                    && !Path.of(fields[5]).equals(Path.of(System.getProperty("user.home"))))
                .findFirst().orElseThrow();
        }
        Path file = directory.resolve("transfer-account.properties");
        Files.writeString(file, "ssh.transfer-user=" + account[0] + "\n");
        var ssh = ServerConfig.load(file).ssh();
        assertEquals(account[0], ssh.transferUser());
        assertEquals(Path.of(account[5], ".ssh", "authorized_keys"), ssh.authorizedKeysFile());
    }

    /**
     * 验证心跳默认间隔为 10 秒、超时为 30 秒，且配置文件能覆盖这两个值。
     * 非正间隔、超时不大于间隔以及非整数配置均必须拒绝加载。
     */
    @Test
    void loadsHeartbeatTimingAndRejectsInvalidIntervals() throws Exception {
        assertEquals(10, ServerConfig.defaults().heartbeatIntervalSeconds());
        assertEquals(30, ServerConfig.defaults().heartbeatTimeoutSeconds());
        Path file = directory.resolve("timing.properties");
        Files.writeString(file, "ownership.heartbeat-interval-seconds=5\nownership.heartbeat-timeout-seconds=20\n");
        assertEquals(5, ServerConfig.load(file).heartbeatIntervalSeconds());
        assertEquals(20, ServerConfig.load(file).heartbeatTimeoutSeconds());
        for (String properties : new String[]{"ownership.heartbeat-interval-seconds=0",
            "ownership.heartbeat-timeout-seconds=0", "ownership.heartbeat-timeout-seconds=10",
            "ownership.heartbeat-interval-seconds=30", "ownership.heartbeat-timeout-seconds=bad"}) {
            Files.writeString(file, properties);
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
        }
    }

    /** 验证数据始终位于当前目录的 data/ 中，拼错的配置项会明确报错。 */
    @Test
    void usesWorkingDirectoryStorageAndRejectsUnknownSettings() throws Exception {
        Path file = directory.resolve("server.properties");
        Files.writeString(file, "http.port=2000\n");
        ServerConfig config = ServerConfig.load(file);
        assertEquals(Path.of("data/users.json"), config.usersFile());
        assertEquals(Path.of("data/worlds"), config.worldsDirectory());
        assertEquals(Path.of("data/transfers"), config.ssh().transferDirectory());
        assertEquals(Path.of("data/tunnel/tls").toAbsolutePath(), config.tunnel().tlsDirectory());
        assertEquals(2000, config.port());
        Files.writeString(file, "http.prot=2000\n");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
    }

    /** 验证生成的配置可直接加载、每项都有说明、默认值正确，重复初始化保留用户修改。 */
    @Test
    void createsDocumentedDefaultsWithoutOverwritingExistingConfiguration() throws Exception {
        Path file = directory.resolve("server.properties");
        Path empty = directory.resolve("empty.properties");
        Files.writeString(empty, "");
        assertTrue(ServerConfig.createDefaultFile(file));
        assertEquals(ServerConfig.load(empty), ServerConfig.load(file));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        assertFalse(properties.isEmpty());
        String previous = "";
        for (String line : text.lines().toList()) {
            if (!line.isBlank() && !line.startsWith("#")) {
                assertTrue(previous.startsWith("# ") && previous.length() > 2, line);
            }
            previous = line;
        }
        Files.writeString(file, "# 自定义配置\nhttp.port=2000\n");
        assertFalse(ServerConfig.createDefaultFile(file));
        assertEquals("# 自定义配置\nhttp.port=2000\n", Files.readString(file));
        assertEquals(2000, ServerConfig.load(file).port());
    }

    /** 验证 --config 只创建完整配置和目录后退出，重复运行会补建目录并保留已有配置。 */
    @Test
    void configurationCommandInitializesAndExits() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path classes = Path.of(WorldRelayServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path config = directory.resolve("server.properties");
        for (int attempt = 0; attempt < 2; attempt++) {
            Path log = directory.resolve("initialize-" + attempt + ".log");
            Process process = new ProcessBuilder(java.toString(), "-Duser.home=" + directory.resolve("home"),
                "-cp", classes.toString(), WorldRelayServer.class.getName(), "--config")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "初始化命令应在创建文件后退出");
                assertEquals(0, process.exitValue(), () -> {
                    try { return Files.readString(log); } catch (Exception failure) { return failure.toString(); }
                });
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
            for (String path : new String[]{"data/worlds", "data/transfers", "data/tunnel/tls", "home/.ssh"})
                assertTrue(Files.isDirectory(directory.resolve(path)), path);
            if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory.resolve("home/.ssh")));
            if (attempt == 0) {
                assertTrue(Files.isRegularFile(config));
                Files.writeString(config, "http.port=2000\n");
                Files.delete(directory.resolve("data/transfers"));
            } else {
                assertEquals("http.port=2000\n", Files.readString(config));
            }
        }
    }

}
