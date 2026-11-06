package top.kmar.mc.worldreplay.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.sync.RsyncCommand;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RsyncTransferTest {

    @TempDir
    Path root;

    /** 验证同步命令启用增量校验和删除同步，并正确处理带空格的路径及 IPv6 地址。 */
    @Test
    void uploadAndDownloadUseDeltaChecksumsDeletionAndExplicitSshWithSpaces() throws Exception {
        var tools = new BundledRsync.Executables(root.resolve("工具 目录/rsync.exe"), root.resolve("SSH 目录/ssh.exe"));
        var upload = RsyncCommand.create("2001:db8::1", 2222, "work", true);
        var push = RsyncTransfer.command(tools, upload, root.resolve("用户 配置/ssh.conf"));
        assertTrue(push.contains("--no-whole-file"));
        assertTrue(push.contains("--checksum"));
        assertTrue(push.contains("--delete-delay"));
        assertFalse(push.contains("--inplace"));
        assertEquals("./", push.get(push.size() - 2));
        assertEquals("work@[2001:db8::1]:./", push.getLast());
        assertTrue(push.get(push.indexOf("-e") + 1).contains("SSH 目录/ssh.exe\" -F \""));
        var pull = RsyncTransfer.command(tools, RsyncCommand.create("2001:db8::1", 2222, "work", false), root.resolve("ssh.conf"));
        assertEquals(push.getLast(), pull.get(pull.size() - 2));
        assertEquals("./", pull.getLast());
    }

    /** 验证凭据输出隐藏私钥，并拒绝通过用户名或主机公钥注入命令参数。 */
    @Test
    void credentialsAreNotPrintedAndRemoteUserCannotContainOptions() {
        String privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET\n-----END OPENSSH PRIVATE KEY-----\n";
        assertFalse(new RsyncTransfer.Credentials(privateKey, "ssh-ed25519 AAAA").toString().contains("SECRET"));
        assertThrows(IllegalArgumentException.class, () -> RsyncCommand.create("files.example", 22, "-oProxyCommand=bad", true));
        assertThrows(IllegalArgumentException.class, () -> new RsyncTransfer.Credentials(privateKey, "ssh-ed25519 AAAA\nHost *"));
        var arguments = new ArrayList<>(RsyncCommand.create("files.example", 22, "work", true).arguments());
        arguments.addFirst("--files-from=private-data");
        assertThrows(IllegalArgumentException.class, () -> new RsyncCommand("files.example", 22, "work", arguments));
    }

}