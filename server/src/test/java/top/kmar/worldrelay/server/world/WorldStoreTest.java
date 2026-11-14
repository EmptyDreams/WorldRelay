package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SuppressWarnings("resource") // 失败路径直接断言 open 抛异常，不会取得可关闭的存储实例。
class WorldStoreTest {

    @TempDir
    Path directory;

    /** 验证重复名称在创建及重启加载后均被拒绝，旧元数据保持完整，名称按原文区分大小写。 */
    @Test
    void duplicateNameDoesNotCreateDirectoryOrChangeExistingWorldAndSurvivesRestart() throws Exception {
        String id;
        String original;
        try (WorldStore store = WorldStore.open(directory)) {
            id = store.create("World🌍").worldId();
            original = Files.readString(directory.resolve(id).resolve("world.json"));
            assertNull(store.create("World🌍"));
            assertEquals(1, store.list().size());
            assertEquals(original, Files.readString(directory.resolve(id).resolve("world.json")));
            try (var entries = Files.list(directory)) {
                assertEquals(2, entries.count());
            }
        }
        try (WorldStore reopened = WorldStore.open(directory)) {
            assertNull(reopened.create("World🌍"));
            assertNotNull(reopened.create("world🌍"));
            assertEquals(original, Files.readString(directory.resolve(id).resolve("world.json")));
        }
    }

    /** 验证 UUID 生成器碰撞已有 ID 或磁盘路径时会重新生成，已有世界和占用路径的内容不被覆盖。 */
    @Test
    void idCollisionRetriesWithoutOverwritingExistingWorldOrDirectory() throws Exception {
        UUID first = UUID.randomUUID();
        UUID occupied = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        var ids = new ArrayDeque<>(List.of(first, first, occupied, next));
        try (WorldStore store = WorldStore.open(directory,
            (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE), ids::removeFirst)) {
            assertEquals(first.toString(), store.create("第一个世界").worldId());
            Path metadata = directory.resolve(first.toString()).resolve("world.json");
            String original = Files.readString(metadata);
            Path occupiedDirectory = Files.createDirectory(directory.resolve(occupied.toString()));
            Path marker = Files.writeString(occupiedDirectory.resolve("marker"), "keep");
            assertEquals(next.toString(), store.create("第二个世界").worldId());
            assertEquals(original, Files.readString(metadata));
            assertEquals("keep", Files.readString(marker));
            assertEquals(2, store.list().size());
            assertTrue(ids.isEmpty());
        }
    }

    /** 验证手动将两个世界元数据改成同名会阻止启动且不改写文件；修复后可重新打开并恢复名称约束。 */
    @Test
    void duplicateNameInMetadataFailsStartupWithoutChangingFilesAndReleasesLock() throws Exception {
        Path first;
        Path second;
        try (WorldStore store = WorldStore.open(directory)) {
            first = directory.resolve(store.create("世界甲").worldId()).resolve("world.json");
            second = directory.resolve(store.create("世界乙").worldId()).resolve("world.json");
        }
        String originalFirst = Files.readString(first);
        String originalSecond = Files.readString(second);
        String duplicate = originalSecond.replace("世界乙", "世界甲");
        Files.writeString(second, duplicate);
        assertThrows(IOException.class, () -> WorldStore.open(directory));
        assertEquals(originalFirst, Files.readString(first));
        assertEquals(duplicate, Files.readString(second));
        Files.writeString(second, originalSecond);
        try (WorldStore reopened = WorldStore.open(directory)) {
            assertEquals(2, reopened.list().size());
            assertNull(reopened.create("世界甲"));
            assertNull(reopened.create("世界乙"));
        }
    }

    /** 验证原子发布失败时不新增记录、不改写旧世界并清理临时目录，排除故障后可重试且重启后保留。 */
    @Test
    void atomicPublicationFailureLeavesNoRecordAndCanBeRetried() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false);
        try (WorldStore store = WorldStore.open(directory, (source, target) -> {
            if (fail.get()) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        })) {
            var existing = store.create("已有世界");
            Path existingMetadata = directory.resolve(existing.worldId()).resolve("world.json");
            String original = Files.readString(existingMetadata);
            fail.set(true);
            assertThrows(AtomicMoveNotSupportedException.class, () -> store.create("重试"));
            assertEquals(1, store.list().size());
            assertEquals(original, Files.readString(existingMetadata));
            try (var entries = Files.list(directory)) {
                assertEquals(2, entries.count());
            }
            fail.set(false);
            assertNotNull(store.create("重试"));
        }
        try (WorldStore reopened = WorldStore.open(directory)) {
            assertEquals(2, reopened.list().size());
        }
    }

    /** 验证将普通文件作为存储根目录时拒绝打开，原文件内容和已有世界均保持完整。 */
    @Test
    void openingNonDirectoryFailsWithoutChangingExistingWorld() throws Exception {
        Path root = directory.resolve("worlds");
        try (WorldStore store = WorldStore.open(root)) {
            var world = store.create("已有世界");
            Path blocker = Files.writeString(directory.resolve("blocker"), "not a directory");
            assertThrows(IOException.class, () -> WorldStore.open(blocker));
            assertEquals("not a directory", Files.readString(blocker));
            assertEquals(1, store.list().size());
            assertTrue(Files.isRegularFile(root.resolve(world.worldId()).resolve("world.json")));
        }
    }

    /** 验证文件锁阻止同一存储及等价路径被重复打开；关闭幂等、释放锁，并禁止已关闭实例继续读写。 */
    @Test
    void locksStorageAndReleasesLockOnClose() throws Exception {
        WorldStore first = WorldStore.open(directory);
        try (first) {
            assertThrows(IOException.class, () -> WorldStore.open(directory));
            assertThrows(IOException.class, () -> WorldStore.open(directory.resolve(".")));
        }
        first.close();
        assertThrows(IllegalStateException.class, first::list);
        assertThrows(IllegalStateException.class, () -> first.create("关闭"));
        try (WorldStore reopened = WorldStore.open(directory)) {
            assertNotNull(reopened.create("重新打开"));
        }
    }

    /**
     * 验证未完成的创建目录被忽略，正式世界元数据缺失、损坏、超限或不匹配时拒绝加载且不改写原文件；
     * 每次加载失败都释放存储锁，修复元数据后可重新打开。
     */
    @Test
    void ignoresIncompleteCreationsButRejectsBrokenPublishedMetadataWithoutChangingIt() throws Exception {
        Path temporary = Files.createDirectory(directory.resolve(".creating-interrupted"));
        Files.writeString(temporary.resolve("world.json"), "{");
        try (WorldStore store = WorldStore.open(directory)) {
            assertTrue(store.list().isEmpty());
        }
        String id = UUID.randomUUID().toString();
        Path metadata = Files.createDirectory(directory.resolve(id)).resolve("world.json");
        assertThrows(IOException.class, () -> WorldStore.open(directory));
        String valid = "{\"formatVersion\":1,\"worldId\":\"" + id
            + "\",\"name\":\"世界\",\"createdAt\":\"2026-11-01T00:00:00Z\"}";
        for (String invalid : new String[]{"{", "{}", valid.replace(id, UUID.randomUUID().toString()),
            valid.replace("2026-11-01T00:00:00Z", "invalid"), valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("\"世界\"", "\" \""), valid.replace("\"name\":", "\"unknown\":"),
            valid.replace("\"name\":", "\"name\":\"重复\",\"name\":"), " ".repeat(4097)}) {
            Files.writeString(metadata, invalid);
            assertThrows(IOException.class, () -> WorldStore.open(directory));
            assertEquals(invalid, Files.readString(metadata));
        }
        Files.write(metadata, new byte[]{(byte) 0xff});
        assertThrows(IOException.class, () -> WorldStore.open(directory));
        Files.writeString(metadata, valid);
        try (WorldStore reopened = WorldStore.open(directory)) {
            assertEquals(1, reopened.list().size()); // 每次加载失败都必须释放锁。
        }
    }

    /** 验证世界目录、元数据和存储锁文件不能使用符号链接，拒绝后不改写链接目标；系统不支持时跳过。 */
    @Test
    void rejectsLinkedWorldDirectoriesMetadataAndLockFile() throws Exception {
        Path root = Files.createDirectory(directory.resolve("worlds"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path link = root.resolve(UUID.randomUUID().toString());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            assumeTrue(false, "OS does not permit symbolic links: " + failure.getClass().getSimpleName());
        }
        assertThrows(IOException.class, () -> WorldStore.open(root));
        Files.delete(link);
        Files.createDirectory(link);
        Path externalMetadata = Files.writeString(outside.resolve("world.json"), "{}");
        Files.createSymbolicLink(link.resolve("world.json"), externalMetadata);
        assertThrows(IOException.class, () -> WorldStore.open(root));
        Files.delete(link.resolve("world.json"));
        Files.delete(link);
        Files.delete(root.resolve(".lock"));
        Files.createSymbolicLink(root.resolve(".lock"), externalMetadata);
        assertThrows(IOException.class, () -> WorldStore.open(root));
        assertEquals("{}", Files.readString(externalMetadata));
    }

    /** 验证名称拒绝空值、首尾空白、控制字符、孤立代理项和超长内容，合法名称中的路径字符不参与目录拼接。 */
    @Test
    void validatesUnicodeNamesWithoutTreatingThemAsPaths() throws Exception {
        try (WorldStore store = WorldStore.open(directory)) {
            for (String invalid : new String[]{"", " ", " 前", "后 ", "a\nb", "a\u0000b", "🌍".repeat(65), "\uD800"}) {
                assertThrows(IllegalArgumentException.class, () -> store.create(invalid));
            }
            assertThrows(IllegalArgumentException.class, () -> store.create(null));
            assertNotNull(store.create("🌍".repeat(64)));
            var world = store.create("../name/世界");
            assertTrue(Files.isRegularFile(directory.resolve(world.worldId()).resolve("world.json")));
        }
    }

}
