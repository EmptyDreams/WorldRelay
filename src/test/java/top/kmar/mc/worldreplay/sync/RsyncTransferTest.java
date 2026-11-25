package top.kmar.mc.worldreplay.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.sync.RsyncCommand;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        assertTrue(push.contains("--info=progress2"));
        assertFalse(upload.arguments().contains("--info=progress2"));
        assertFalse(push.contains("--inplace"));
        assertEquals("./", push.get(push.size() - 2));
        assertEquals("work@[2001:db8::1]:./", push.getLast());
        assertTrue(push.get(push.indexOf("-e") + 1).contains("SSH 目录/ssh.exe\" -F \""));
        var pull = RsyncTransfer.command(tools, RsyncCommand.create("2001:db8::1", 2222, "work", false), root.resolve("ssh.conf"));
        assertTrue(pull.contains("--info=progress2"));
        assertEquals(push.getLast(), pull.get(pull.size() - 2));
        assertEquals("./", pull.getLast());
    }

    /** 回车进度和超长的无关输出不会吞掉结尾的增量统计。 */
    @Test
    void readsProgressAndFinalStatsAfterLargeOutput() throws Exception {
        String output = "\r     32,768  25%    1.25MB/s    0:00:04  "
            + "\r     65,536  50%  512.00kB/s    ??:??:?? (xfr#1, ir-chk=7/8)\r\n"
            + "a".repeat(65536) + "\n"
            + "\r    131,072 100%  865KiB/s    0:00:00 (xfr#2, to-chk=0/8)\n"
            + "\r    131,072 123%  999MB/s    0:00:00\n"
            + "Literal data: 1,024 bytes\r\nMatched data: 130,048 bytes";
        var samples = new ArrayList<RsyncTransfer.Progress>();
        var result = RsyncTransfer.readOutput(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), samples::add);
        assertEquals(List.of(new RsyncTransfer.Progress(25, "1.25MB/s"),
            new RsyncTransfer.Progress(50, "512.00kB/s"), new RsyncTransfer.Progress(100, "865KiB/s")), samples);
        assertEquals(new RsyncTransfer.Result(1024, 130048), result);
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
