package top.kmar.mc.worldreplay.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.sync.RsyncMode;
import top.kmar.mc.worldreplay.sync.RsyncTools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ClientConnectionSettingsTest {

    @TempDir
    Path temporary;

    /**
     * 验证中文和空格路径可以随连接信息一起保存、重新读取；程序后来被移走时仍保留路径，
     * 让用户能在设置页修正，不因工具缺失而丢失服务器配置。
     */
    @Test
    void roundTripsCustomPathAndKeepsItAfterExecutableDisappears() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("中文 工具"));
        Path executable = Files.writeString(directory.resolve("rsync.exe"), "placeholder");
        Path ssh = temporary.resolve("independent ssh.exe");
        Path file = temporary.resolve("config/client.json");
        var expected = settings(RsyncMode.CUSTOM, "  " + executable + "  ", ssh.toString());

        expected.save(file);
        assertEquals(expected, ClientConnectionSettings.load(file));
        assertEquals(executable.toString(), expected.rsyncPath());
        assertEquals(ssh.toString(), expected.sshPath());
        assertEquals(RsyncMode.CUSTOM, expected.rsyncMode());
        Files.delete(executable);

        assertEquals(expected, ClientConnectionSettings.load(file));
        assertFalse(Files.exists(executable));
        assertFalse(expected.toString().contains(expected.token()));
    }

    /** 验证来源独立保存，自动模式不校验未启用的自定义草稿，也不因为路径非空而转为自定义模式。 */
    @Test
    void explicitAutoModeIgnoresInactiveCustomDrafts() throws IOException {
        Path file = temporary.resolve("client.json");
        settings(RsyncMode.AUTO, "unfinished rsync path", "unfinished ssh path").save(file);

        var loaded = ClientConnectionSettings.load(file);
        assertEquals(RsyncMode.AUTO, loaded.rsyncMode());
        assertEquals("unfinished rsync path", loaded.rsyncPath());
        assertEquals("unfinished ssh path", loaded.sshPath());
    }

    /** 验证相对路径和含控制字符的输入在表单语法校验阶段被拒绝，避免将命令当作程序路径保存。 */
    @Test
    void rejectsRelativePathsAndControlCharacters() {
        for (String path : new String[]{"rsync.exe", "tools/rsync", temporary + "\n--archive", temporary + "\0rsync"}) {
            var error = assertThrows(ClientConnectionSettings.ValidationException.class,
                () -> settings(RsyncMode.CUSTOM, path, temporary.resolve("ssh.exe").toString()));
            assertEquals("worldrelay.settings.error.rsync_path_invalid", error.translationKey());
            var sshError = assertThrows(ClientConnectionSettings.ValidationException.class,
                () -> settings(RsyncMode.CUSTOM, temporary.resolve("rsync.exe").toString(), path));
            assertEquals("worldrelay.settings.error.ssh_path_invalid", sshError.translationKey());
        }
    }

    /**
     * 验证自定义工具校验失败时不发送 ping、不覆盖原配置，且异步异常能映射到路径错误提示。
     * 地址使用不可解析的测试域名；路径检查必须在任何网络请求之前失败。
     */
    @Test
    void invalidCustomExecutableDoesNotOverwriteSavedSettings() throws IOException {
        Path file = temporary.resolve("client.json");
        settings(RsyncMode.defaultMode(), "", "").save(file);
        byte[] original = Files.readAllBytes(file);
        var invalid = settings(RsyncMode.CUSTOM, temporary.resolve("missing-rsync.exe").toString(),
            temporary.resolve("ssh.exe").toString());

        try (var client = HttpClient.newHttpClient()) {
            var error = assertThrows(RsyncTools.ValidationException.class,
                () -> ClientSettingsVerifier.verifyAndSave(invalid, "Player", file, client, Duration.ofMillis(100)));
            assertEquals("error.rsync_path_unavailable", ClientSettingsVerifier.errorKey(new CompletionException(error)));
        }

        assertArrayEquals(original, Files.readAllBytes(file));
    }

    /** 验证未知模式被拒绝加载，不把错误配置静默改为另一种工具来源，也不在异常中泄露 Token。 */
    @Test
    void rejectsUnknownModeWithoutLeakingCredentials() throws IOException {
        Path file = Files.writeString(temporary.resolve("invalid.json"), """
            {"serverAddress":"https://relay.example","serverPort":443,"token":"test-token",
             "rsyncMode":"UNKNOWN","rsyncPath":"","sshPath":"","rsyncAddress":"files.example","rsyncPort":22}
            """);

        var error = assertThrows(IOException.class, () -> ClientConnectionSettings.load(file));

        assertFalse(error.toString().contains("test-token"));
        assertNull(error.getCause());
    }

    /** 验证接口和 rsync 使用不同主机、端口时分别保存和还原，接口 URI 不受 SSH 端点影响。 */
    @Test
    void savesApiAndRsyncEndpointsIndependently() throws IOException {
        var settings = ClientConnectionSettings.fromInput("https://api.example", "8443", "test-token",
            RsyncMode.AUTO, "", "", "FILES.example", "2222");
        Path file = temporary.resolve("independent.json");

        settings.save(file);
        var loaded = ClientConnectionSettings.load(file);

        assertEquals(settings, loaded);
        assertEquals(URI.create("https://api.example:8443"), loaded.serverUri());
        assertEquals("files.example", loaded.rsyncEndpoint().getHostString());
        assertEquals(2222, loaded.rsyncEndpoint().getPort());
        assertTrue(loaded.rsyncEndpoint().isUnresolved());
    }

    /** 验证远程地址支持 IPv6，可接受完整方括号输入，并且地址解析不会触发 DNS 查询。 */
    @Test
    void acceptsIpv6RsyncAddressWithoutResolvingIt() {
        for (String address : new String[]{"2001:db8::1", "[2001:db8::1]"}) {
            var settings = remoteSettings(address, "22");
            assertEquals("2001:db8::1", settings.rsyncAddress());
            assertEquals(22, settings.rsyncEndpoint().getPort());
            assertTrue(settings.rsyncEndpoint().isUnresolved());
        }
    }

    /** 验证远程地址必填，且拒绝 URL、用户名、内嵌端口、路径和命令参数，避免混淆 SSH 连接目标。 */
    @Test
    void rejectsMalformedRsyncAddresses() {
        var missing = assertThrows(ClientConnectionSettings.ValidationException.class, () -> remoteSettings("", "22"));
        assertEquals("worldrelay.settings.error.rsync_address_required", missing.translationKey());
        for (String address : new String[]{"https://files.example", "user@files.example", "files.example:2222",
            "files.example/path", "files.example?arg=value", "-oProxyCommand=command", "host name", "[::1]:2222"}) {
            var error = assertThrows(ClientConnectionSettings.ValidationException.class, () -> remoteSettings(address, "22"));
            assertEquals("worldrelay.settings.error.rsync_address_invalid", error.translationKey());
        }
    }

    /** 验证 SSH 端口独立校验，拒绝缺失、越界和非数字输入，不误报为接口端口错误。 */
    @Test
    void rejectsInvalidRsyncPorts() {
        for (String port : new String[]{"", "0", "65536", "-1", "22x", "2222222222"}) {
            var error = assertThrows(ClientConnectionSettings.ValidationException.class,
                () -> remoteSettings("files.example", port));
            assertEquals("worldrelay.settings.error.rsync_port_invalid", error.translationKey());
        }
    }

    private static ClientConnectionSettings remoteSettings(String address, String port) {
        return ClientConnectionSettings.fromInput("https://api.example", "443", "test-token",
            RsyncMode.AUTO, "", "", address, port);
    }

    private static ClientConnectionSettings settings(RsyncMode mode, String rsyncPath, String sshPath) {
        return ClientConnectionSettings.fromInput("https://relay.example.invalid", "443", "test-token", mode, rsyncPath, sshPath,
            "files.example.invalid", "2222");
    }

}
