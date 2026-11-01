package top.kmar.mc.worldreplay.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RsyncToolsTest {

    @TempDir
    Path temporary;

    /** 验证自定义模式使用用户明确指定的 SSH，即使 rsync 同目录另有 SSH，也不能替换用户选择。 */
    @Test
    void customModeUsesIndependentlySelectedSsh() throws IOException {
        Path rsync = executable(temporary.resolve("custom"), "rsync.exe");
        executable(rsync.getParent(), "ssh.exe");
        Path otherSsh = executable(temporary.resolve("other"), "ssh.exe");

        var tools = RsyncTools.customExecutables(rsync.toString(), otherSsh.toString());

        assertEquals(rsync, tools.rsync());
        assertEquals(otherSsh, tools.ssh());
    }

    /** 验证自动查找能从不同的 PATH 目录中找到两个已安装工具，并支持中文和空格路径。 */
    @Test
    void autoModeFindsBothToolsOnPath() throws IOException {
        Path rsync = executable(temporary.resolve("custom"), "rsync");
        Path ssh = executable(temporary.resolve("中文 SSH 工具"), "ssh");

        var tools = RsyncTools.findInstalled(false, rsync.getParent() + File.pathSeparator + ssh.getParent());

        assertEquals(rsync, tools.rsync());
        assertEquals(ssh, tools.ssh());
    }

    /** 验证选择自定义工具时直接返回指定程序，不提取或改写内置工具，也不要求存在游戏目录。 */
    @Test
    void customToolsDoNotTriggerBundledExtraction() throws IOException {
        Path rsync = executable(temporary.resolve("custom"), "rsync.exe");
        Path ssh = executable(temporary.resolve("separate-ssh"), "ssh.exe");
        Path game = temporary.resolve("uncreated-game");
        byte[] original = Files.readAllBytes(rsync);

        var tools = RsyncTools.prepare(game, RsyncMode.CUSTOM, rsync.toString(), ssh.toString());

        assertEquals(new BundledRsync.Executables(rsync, ssh), tools);
        assertArrayEquals(original, Files.readAllBytes(rsync));
        assertFalse(Files.exists(game));
    }

    /** 验证缺失的程序或误填的目录被明确拒绝，不悄悄回退到内置 rsync。 */
    @Test
    void missingExecutableAndDirectoryAreRejected() throws IOException {
        Path ssh = executable(temporary.resolve("ssh"), "ssh.exe");
        for (Path path : new Path[]{temporary.resolve("missing-rsync.exe"), Files.createDirectory(temporary.resolve("directory"))}) {
            var error = assertThrows(RsyncTools.ValidationException.class,
                () -> RsyncTools.customExecutables(path.toString(), ssh.toString()));
            assertEquals("error.rsync_path_unavailable", error.errorKey());
        }
    }

    /** 验证自动模式忽略空 PATH 项、相对目录和无效路径，不搜索工作目录，也不回退到内置工具。 */
    @Test
    void unusablePathEntriesDoNotSelectTools() {
        String searchPath = File.pathSeparator + "relative-bin" + File.pathSeparator + "invalid\0directory";

        var error = assertThrows(RsyncTools.ValidationException.class,
            () -> RsyncTools.findInstalled(true, searchPath));

        assertEquals("error.rsync_not_found", error.errorKey());
    }

    /** 验证自动模式使用 Windows 的 exe 名称，并在缺少 SSH 时返回可区分的错误。 */
    @Test
    void autoModeRequiresSshAsWellAsRsync() throws IOException {
        Path rsync = executable(temporary.resolve("installed"), "rsync.exe");
        var error = assertThrows(RsyncTools.ValidationException.class,
            () -> RsyncTools.findInstalled(true, rsync.getParent().toString()));
        assertEquals("error.ssh_not_found", error.errorKey());

        Path ssh = executable(rsync.getParent(), "ssh.exe");
        assertEquals(new BundledRsync.Executables(rsync, ssh),
            RsyncTools.findInstalled(true, rsync.getParent().toString()));
    }

    /** 验证自定义 SSH 路径无效时明确失败，即使 rsync 同目录中存在可用的 SSH，也不能自动替代。 */
    @Test
    void invalidCustomSshDoesNotFallBackToSibling() throws IOException {
        Path rsync = executable(temporary.resolve("custom"), "rsync.exe");
        executable(rsync.getParent(), "ssh.exe");

        var error = assertThrows(RsyncTools.ValidationException.class,
            () -> RsyncTools.customExecutables(rsync.toString(), temporary.resolve("missing-ssh.exe").toString()));

        assertEquals("error.ssh_path_unavailable", error.errorKey());
    }

    private static Path executable(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Path file = Files.writeString(directory.resolve(name), "placeholder");
        // 只检查路径选择，不执行测试文件；POSIX 平台需设置执行权限。
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

}
