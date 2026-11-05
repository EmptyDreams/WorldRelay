package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SnapshotFilesTest {

    @TempDir
    Path root;
    private final String compatibility = "b".repeat(64);

    /** 验证清单编解码、空目录和会话锁排除，并识别大小及时间戳相同的内容变化。 */
    @Test
    void roundTripVerifiesAllFilesEmptyDirectoriesAndSameSizeChanges() throws Exception {
        Path world = Files.createDirectory(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("session.lock"), "machine-only-lock");
        Files.createDirectories(world.resolve("模组数据/空目录"));
        Path file = Files.writeString(world.resolve("模组数据/train.dat"), "one");
        var before = Files.getLastModifiedTime(file);
        var manifest = SnapshotFiles.scan(world, compatibility);
        assertFalse(manifest.entries().stream().anyMatch(e -> e.path().equals("session.lock")));
        assertTrue(manifest.entries().stream().anyMatch(e -> e.path().equals("模组数据/空目录") && e.directory()));
        assertEquals(manifest, SnapshotManifest.decode(manifest.encode()));
        Files.writeString(file, "two");
        Files.setLastModifiedTime(file, before);
        assertThrows(IOException.class, () -> SnapshotFiles.verify(world, manifest));
    }

    /** 验证清单拒绝路径穿越、大小写冲突、保留名称及缺少父目录的条目。 */
    @Test
    void manifestsRejectTraversalCaseAliasesReservedPathsAndBrokenParents() {
        for (String path : List.of("../escape", "/absolute", "a/../b", "a\\b", "C:/x", "a//b", "con.txt", "trailing.", "a\nfile")) {
            assertThrows(IllegalArgumentException.class, () -> new SnapshotManifest.Entry(path, true, 0, ""), path);
        }
        var level = new SnapshotManifest.Entry("level.dat", false, 1, "c".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotManifest(compatibility,
            List.of(new SnapshotManifest.Entry("LEVEL.DAT", false, 1, "c".repeat(64)), level)));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotManifest(compatibility,
            List.of(level, new SnapshotManifest.Entry("missing/file", false, 1, "c".repeat(64)))));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotManifest(compatibility, List.of()));
    }

    /** 验证旧写入者持有的文件句柄不能修改已封存的独立副本。 */
    @Test
    void sealedCopiesAreSafeEvenWhileAnOldWriterKeepsItsFileDescriptor() throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("level.dat"), "base");
        Path target = root.resolve("candidate");
        try (var writer = java.nio.channels.FileChannel.open(source.resolve("level.dat"), StandardOpenOption.WRITE)) {
            SnapshotFiles.copyIsolated(source, target, false);
            assertEquals(4, writer.write(java.nio.ByteBuffer.wrap("late".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            writer.force(true);
        }
        assertEquals("base", Files.readString(target.resolve("level.dat")));
        assertEquals("late", Files.readString(source.resolve("level.dat")));
    }

    /** 验证存档扫描和安全复制均拒绝符号链接，防止越过存档目录。 */
    @Test
    void symbolicLinksCannotEscapeSecureTraversal() throws Exception {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("level.dat"), "base");
        Files.createSymbolicLink(source.resolve("escape"), root);
        assertThrows(IOException.class, () -> SnapshotFiles.copyIsolated(source, root.resolve("rejected"), true));
        assertThrows(IOException.class, () -> SnapshotFiles.scan(source, compatibility));
    }

}