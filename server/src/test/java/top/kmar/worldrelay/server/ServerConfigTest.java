package top.kmar.worldrelay.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /** 验证配置文件选择唯一入口：WSS 仅监听回环，TCP 接受 IP 或域名并使用独立 TLS 目录。 */
    @Test
    void selectsTunnelModeAndAdvertisesConfiguredTcpEndpoint() throws Exception {
        Path file = directory.resolve("tunnel.properties");
        Files.writeString(file, "http.port=0\nregistration.port=0\ntunnel.mode=wss\ntunnel.port=0\n");
        var wss = ServerConfig.load(file).tunnel();
        assertEquals(top.kmar.worldrelay.server.tunnel.TunnelConfig.Mode.WSS, wss.mode());
        assertEquals("127.0.0.1", wss.bindAddress());
        for (String host : new String[]{"121.89.94.223", "relay.example.com", "2001:db8::1"}) {
            Files.writeString(file, "tunnel.mode=tcp\ntunnel.port=2002\ntunnel.public-host=" + host + "\ntunnel.public-port=3002\ntunnel.tls-directory=runtime/tls\n");
            var tcp = ServerConfig.load(file).tunnel();
            assertEquals(top.kmar.worldrelay.server.tunnel.TunnelConfig.Mode.TCP, tcp.mode());
            assertEquals("0.0.0.0", tcp.bindAddress());
            assertEquals(host, tcp.publicHost());
            assertEquals(3002, tcp.publicPort());
            assertEquals(Path.of("runtime/tls"), tcp.tlsDirectory());
        }
        Files.writeString(file, "tunnel.mode=wss\ntunnel.bind-address=0.0.0.0\n");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
    }

    /** 验证 SSH 路径及对外连接地址由部署配置指定，非法的必填配置被拒绝。 */
    @Test
    void loadsSshRegistrationPathsAndRejectsEmptyValues() throws Exception {
        Path file = directory.resolve("ssh.properties");
        Files.writeString(file, "auth.users-file=custom/users.json\n");
        var defaults = ServerConfig.load(file).ssh();
        assertEquals(Path.of("custom/ssh/authorized_keys"), defaults.authorizedKeysFile());
        assertEquals(Path.of("custom/transfers"), defaults.transferDirectory());
        Files.writeString(file, """
            ssh.authorized-keys-file=keys/authorized_keys
            ssh.transfer-directory=staging/users
            ssh.rrsync-command=/usr/local/bin/rrsync
            ssh.public-host=[2001:db8::1]
            ssh.public-port=2222
            """);
        var ssh = ServerConfig.load(file).ssh();
        assertEquals(Path.of("keys/authorized_keys"), ssh.authorizedKeysFile());
        assertEquals(Path.of("staging/users"), ssh.transferDirectory());
        assertEquals("/usr/local/bin/rrsync", ssh.rrsyncCommand());
        assertEquals("2001:db8::1", ssh.publicHost());
        assertEquals(2222, ssh.publicPort());
        for (String key : new String[]{"ssh.authorized-keys-file", "ssh.transfer-directory", "ssh.rrsync-command", "ssh.public-host"}) {
            Files.writeString(file, key + "=\n");
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
        }
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

    /** 验证世界目录支持 UTF-8 配置，旧构造器保留默认值，空目录配置及未知配置项被拒绝。 */
    @Test
    void loadsWorldDirectoryAndRetainsExistingDefaultsAndConstructors() throws Exception {
        Path path = Path.of("data/worlds");
        assertEquals(path, ServerConfig.defaults().worldsDirectory());
        assertEquals(path,
            new ServerConfig("127.0.0.1", 0, 1, 0, 0, Path.of("users.json")).worldsDirectory());
        Path file = directory.resolve("server.properties");
        Files.writeString(file, "worlds.directory=自定义/worlds\n");
        ServerConfig config = ServerConfig.load(file);
        assertEquals(Path.of("自定义/worlds"), config.worldsDirectory());
        assertEquals(ServerConfig.defaults().usersFile(), config.usersFile());
        Files.writeString(file, "worlds.directory=\n");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
        Files.writeString(file, "worlds.unknown=value\n");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
    }

}
