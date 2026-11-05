package top.kmar.mc.worldreplay.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RsyncTransferTest {

    @TempDir
    Path root;

    /** 验证同步命令启用增量校验和删除同步，并正确处理带空格的路径及 IPv6 地址。 */
    @Test
    void uploadAndDownloadUseDeltaChecksumsDeletionAndExplicitSshWithSpaces() throws Exception {
        var tools = new BundledRsync.Executables(root.resolve("工具 目录/rsync.exe"), root.resolve("SSH 目录/ssh.exe"));
        var endpoint = InetSocketAddress.createUnresolved("2001:db8::1", 2222);
        var push = RsyncTransfer.command(tools, endpoint, "work", root.resolve("用户 配置/ssh.conf"), true);
        assertTrue(push.contains("--no-whole-file"));
        assertTrue(push.contains("--checksum"));
        assertTrue(push.contains("--delete-delay"));
        assertFalse(push.contains("--inplace"));
        assertEquals("./", push.get(push.size() - 2));
        assertEquals("work@[2001:db8::1]:./", push.getLast());
        assertTrue(push.get(push.indexOf("-e") + 1).contains("SSH 目录/ssh.exe\" -F \""));
        var pull = RsyncTransfer.command(tools, endpoint, "work", root.resolve("ssh.conf"), false);
        assertEquals(push.getLast(), pull.get(pull.size() - 2));
        assertEquals("./", pull.getLast());
    }

    /** 验证凭据输出隐藏私钥，并拒绝通过用户名或主机公钥注入命令参数。 */
    @Test
    void credentialsAreNotPrintedAndRemoteUserCannotContainOptions() {
        String privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET\n-----END OPENSSH PRIVATE KEY-----\n";
        assertFalse(new RsyncTransfer.Credentials(privateKey, "work", "ssh-ed25519 AAAA").toString().contains("SECRET"));
        assertThrows(IllegalArgumentException.class, () -> new RsyncTransfer.Credentials(privateKey, "-oProxyCommand=bad", "ssh-ed25519 AAAA"));
        assertThrows(IllegalArgumentException.class, () -> new RsyncTransfer.Credentials(privateKey, "work", "ssh-ed25519 AAAA\nHost *"));
    }

}