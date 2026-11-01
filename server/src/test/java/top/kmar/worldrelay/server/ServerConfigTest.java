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
