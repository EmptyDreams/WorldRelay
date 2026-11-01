package top.kmar.mc.worldreplay.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BundledRsyncTest {

    @TempDir
    Path temporary;

    /**
     * 验证中文和空格路径下，程序及许可文件直接提取到与 mods 同级的工具目录；
     * 不提取安装脚本，也不改写已有模组文件。
     */
    @Test
    void extractsCompleteBundleBesideModsAndPreservesOtherFiles() throws IOException {
        Path game = Files.createDirectory(temporary.resolve("中文 游戏目录"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        Files.writeString(mods.resolve("existing-mod.jar"), "do not change");

        var tools = extract(game);

        assertTrue(tools.rsync().isAbsolute());
        assertEquals(tools.rsync().getParent(), tools.ssh().getParent());
        assertEquals(game.resolve("worldrelay-tools"), tools.rsync().getParent());
        assertTrue(Files.size(tools.rsync()) > 0);
        assertTrue(Files.size(tools.ssh()) > 0);
        assertTrue(Files.isRegularFile(tools.rsync().resolveSibling("COPYING.txt")));
        assertTrue(Files.isRegularFile(tools.rsync().resolveSibling("NOTICE-ssh.txt")));
        assertTrue(Files.isRegularFile(tools.rsync().resolveSibling("RELEASE-NOTES.txt")));
        assertFalse(Files.exists(tools.rsync().resolveSibling("setup-windows-rsync.ps1")));
        assertEquals("do not change", Files.readString(mods.resolve("existing-mod.jar")));
    }

    /**
     * 验证重复提取会复用校验通过的程序；通过保留修改时间确认没有无意义的覆盖写入。
     */
    @Test
    void reusesVerifiedFilesWithoutRewritingThem() throws IOException {
        var tools = extract(temporary);
        FileTime oldTime = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(tools.rsync(), oldTime);
        Files.setLastModifiedTime(tools.ssh(), oldTime);

        assertEquals(tools, extract(temporary));

        assertEquals(oldTime, Files.getLastModifiedTime(tools.rsync()));
        assertEquals(oldTime, Files.getLastModifiedTime(tools.ssh()));
    }

    /**
     * 验证内容损坏即使未改变文件大小和修改时间也会被发现并修复，
     * 同时补齐缺失的 SSH 程序和许可文件，防止仅凭文件存在就复用不完整的工具包。
     */
    @Test
    void repairsModifiedExecutableEvenWithSameSizeAndTimestampAndRestoresMissingFiles() throws IOException {
        var tools = extract(temporary);
        byte[] original = Files.readAllBytes(tools.rsync());
        byte[] modified = original.clone();
        modified[modified.length / 2] ^= 1;
        FileTime originalTime = Files.getLastModifiedTime(tools.rsync());
        Files.write(tools.rsync(), modified);
        Files.setLastModifiedTime(tools.rsync(), originalTime);
        Files.delete(tools.ssh());
        Path notice = tools.rsync().resolveSibling("NOTICE-ssh.txt");
        Files.delete(notice);

        assertEquals(tools, extract(temporary));

        assertArrayEquals(original, Files.readAllBytes(tools.rsync()));
        assertTrue(Files.size(tools.ssh()) > 0);
        assertTrue(Files.isRegularFile(notice));
    }

    /**
     * 验证同进程内两个线程并发提取时都能成功取得同一组路径，不因文件访问冲突而失败。
     */
    @Test
    void concurrentCallsShareOneCompleteInstallation() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> extract(temporary));
            var second = executor.submit(() -> extract(temporary));
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    /**
     * 验证仅接受 Windows x64；其他系统或架构必须在写入前被拒绝，不创建游戏或工具目录。
     */
    @Test
    void unsupportedPlatformsDoNotCreateDirectories() {
        for (String[] platform : new String[][]{
            {"Windows 11", "x86"}, {"Windows 11", "aarch64"}, {"Linux", "amd64"}, {"Mac OS X", "x86_64"}
        }) {
            Path game = temporary.resolve("uncreated");
            assertThrows(UnsupportedOperationException.class,
                () -> BundledRsync.extract(game, platform[0], platform[1]));
            assertFalse(Files.exists(game));
        }
        assertTrue(BundledRsync.isSupportedPlatform("Windows 11", "amd64"));
        assertTrue(BundledRsync.isSupportedPlatform("Windows 10", "x86_64"));
    }

    /**
     * 验证程序路径被目录占用时明确报错，并保留该目录及其内容，防止修复过程误删已有数据。
     */
    @Test
    void refusesDirectoryInPlaceOfExecutableWithoutDeletingItsContents() throws IOException {
        var tools = extract(temporary);
        Files.delete(tools.ssh());
        Files.createDirectory(tools.ssh());
        Path unrelated = Files.writeString(tools.ssh().resolve("keep.txt"), "keep");

        assertThrows(IOException.class, () -> extract(temporary));
        assertEquals("keep", Files.readString(unrelated));
    }

    /**
     * 验证工具目录路径被普通文件占用时停止提取，保留原文件内容，不擅自覆盖或删除。
     */
    @Test
    void refusesFileInPlaceOfToolsDirectoryWithoutOverwritingIt() throws IOException {
        Path occupied = Files.writeString(temporary.resolve("worldrelay-tools"), "keep");

        assertThrows(IOException.class, () -> extract(temporary));
        assertEquals("keep", Files.readString(occupied));
    }

    private static BundledRsync.Executables extract(Path game) throws IOException {
        // 仅提取、不执行 Windows 程序，因此文件校验测试也可以在其他系统运行。
        return BundledRsync.extract(game, "Windows 11", "amd64");
    }

}
