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