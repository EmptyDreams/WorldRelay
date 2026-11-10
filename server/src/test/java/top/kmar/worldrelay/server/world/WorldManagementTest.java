package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.TransferTestUsers;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class WorldManagementTest {

    @TempDir
    Path root;

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class TestClock extends Clock {

        Instant now = Instant.parse("2026-11-09T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

    }

    private WorldRetention retention(WorldStore store, Clock clock) {
        return new WorldRetention(store, root.resolve("transfers"), new RetentionConfig(5, 0, 24, 0, 60, ZoneOffset.UTC), clock, ignored -> Long.MAX_VALUE);
    }

    /** 删除与获取所有权并发时，只有一方能成功；删除不能越过有效持有者。 */
    @Test
    void deletionAndOwnershipAcquisitionAreAtomic() throws Exception {
        try (var store = WorldStore.open(root.resolve("worlds")); var retention = retention(store, Clock.systemUTC());
             var jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            String world = java.util.Objects.requireNonNull(store.create("race")).worldId();
            var preview = retention.previewDeletion(world);
            var start = new CountDownLatch(1);
            var deletion = jobs.submit(() -> {
                start.await();
                try {
                    retention.delete(preview);
                    return "deleted";
                } catch (WorldAccessException failure) {
                    assertEquals("WORLD_OWNED", failure.code());
                    return "blocked";
                }
            });
            var acquisition = jobs.submit(() -> {
                start.await();
                try {
                    store.acquire(world, "Dev", BackupTestWorld.id(), BackupTestWorld.id());
                    return "owned";
                } catch (WorldAccessException failure) {
                    assertEquals("WORLD_NOT_FOUND", failure.code());
                    return "blocked";
                }
            });
            start.countDown();
            boolean deleted = deletion.get().equals("deleted"), owned = acquisition.get().equals("owned");
            assertNotEquals(deleted, owned);
            assertEquals(owned, Files.exists(root.resolve("worlds").resolve(world)));
            assertEquals(owned ? 1 : 0, store.list().size());
        }
    }

    /** 活动复制阻止删除；回收副本保留 24 小时，过期后只清理目标世界及其孤立传输目录。 */
    @SuppressWarnings({"try", "unused"}) // operation 的资源生命周期用于阻止删除。
    @Test
    void deletionRetainsBackupUntilExpiryAndProtectsActiveOperations() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        var clock = new TestClock();
        try (var store = WorldStore.open(data.root(), WorldManagementTest::move, UUID::randomUUID,
            WorldManagementTest::move, System::nanoTime, 10, 30, clock); var retention = retention(store, clock)) {
            String other = java.util.Objects.requireNonNull(store.create("other")).worldId();
            var preview = retention.previewDeletion(data.world());
            try (var operation = retention.protect(data.world())) {
                assertEquals("WORLD_BUSY", assertThrows(WorldAccessException.class, () -> retention.delete(preview)).code());
                assertEquals("WORLD_BUSY", assertThrows(WorldAccessException.class, () -> retention.previewDeletion(data.world())).code());
            }
            Path orphan = root.resolve("transfers/.sessions").resolve("a".repeat(64)).resolve(data.world()).resolve("2/upload-" + BackupTestWorld.id());
            Files.createDirectories(orphan);
            Files.writeString(orphan.resolve("temporary"), "old transfer");
            Files.setLastModifiedTime(orphan, FileTime.from(Instant.EPOCH));
            Instant deletedAt = retention.delete(preview);
            assertEquals(clock.now, deletedAt);
            Path archive = data.root().resolve(".deleted-" + data.world() + "-" + preview.deleteId());
            assertEquals("second-world", Files.readString(archive.resolve("snapshots").resolve(data.second()).resolve("world/level.dat")));
            retention.sweep();
            assertTrue(Files.isDirectory(archive));
            assertFalse(Files.exists(orphan));
            clock.now = clock.now.plusSeconds(25 * 3600);
            retention.sweep();
            assertFalse(Files.exists(archive));
            assertTrue(Files.isRegularFile(data.root().resolve(other).resolve("world.json")));
        }
    }

    /** 原子删除已经移动目录但响应失败时，重试可识别同一次删除，不重新暴露旧世界。 */
    @Test
    void deletionRetriesAfterUncertainDirectoryPublication() throws Exception {
        var fail = new AtomicBoolean();
        try (var store = WorldStore.open(root.resolve("worlds"), (source, target) -> {
            move(source, target);
            if (target.getFileName().toString().startsWith(".deleted-") && fail.getAndSet(false)) throw new IOException("after move");
        }, UUID::randomUUID, WorldManagementTest::move, System::nanoTime, 10, 30); var retention = retention(store, Clock.systemUTC())) {
            String world = java.util.Objects.requireNonNull(store.create("uncertain")).worldId();
            var preview = retention.previewDeletion(world);
            fail.set(true);
            assertThrows(IOException.class, () -> retention.delete(preview));
            assertTrue(store.list().isEmpty());
            assertNotNull(retention.delete(preview));
            var changed = new WorldStore.Deletion(world, preview.deleteId(), "different", preview.expectedEpoch(), null, preview.metadataHash());
            assertEquals("DELETE_CONFLICT", assertThrows(WorldAccessException.class, () -> retention.delete(changed)).code());
        }
    }

    /** 改名发布结果不确定时预留新旧名称，避免另建同名世界后导致重启无法加载。 */
    @Test
    void uncertainRenameReservesBothNamesAndRemainsQueryable() throws Exception {
        var fail = new AtomicBoolean();
        try (var store = WorldStore.open(root.resolve("worlds"), WorldManagementTest::move, UUID::randomUUID, (source, target) -> {
            move(source, target);
            if (fail.getAndSet(false)) throw new IOException("after metadata move");
        }, System::nanoTime, 10, 30)) {
            String world = java.util.Objects.requireNonNull(store.create("before")).worldId();
            fail.set(true);
            assertThrows(IOException.class, () -> store.rename(world, "before", "after"));
            assertNull(store.create("after"));
            assertNull(store.create("before"));
            assertTrue(store.adminState(world).unavailable());
            assertTrue(new WorldAdministration(store).list().contains("\"unavailable\":true"));
        }
        try (var store = WorldStore.open(root.resolve("worlds"))) {
            assertEquals("after", store.list().getFirst().name());
            assertNotNull(store.create("before"));
        }
    }

    /** 已经进入上传阶段的旧请求在用户禁用后仍不能发布，启用后可在原任期重试。 */
    @Test
    void disabledUserCannotPublishAnAlreadyPreparedSnapshot() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        Path usersFile = root.resolve("users.json");
        try (var users = TransferTestUsers.open(usersFile, TransferTestUsers.config(usersFile)); var store = WorldStore.open(data.root())) {
            assertNotNull(users.register("Dev", false));
            store.configureUsers(users::isEnabled);
            var owner = BackupTestWorld.acquire(store, data.world());
            store.stop(data.world(), owner, WorldStore.StopStage.BEGIN, 2L);
            store.prepareTransfer(data.world(), owner, 2L, true);
            Path candidate = store.candidateDirectory(data.world(), owner).resolve("payload");
            Files.createDirectories(candidate.resolve("world"));
            Files.writeString(candidate.resolve("world/level.dat"), "third-world");
            byte[] manifest = SnapshotFiles.scan(candidate.resolve("world")).encode();
            Files.write(candidate.resolve("manifest.bin"), manifest);
            String upload = BackupTestWorld.id(), hash = SnapshotManifest.hash(manifest);
            users.setEnabled("Dev", false);
            assertEquals("USER_DISABLED", assertThrows(WorldAccessException.class,
                () -> store.publishSnapshot(data.world(), owner, 2L, upload, hash, candidate)).code());
            assertEquals(2L, store.adminState(data.world()).metadata().state().committedVersion());
            assertEquals(owner.epoch(), store.adminState(data.world()).metadata().state().epoch());
            users.setEnabled("Dev", true);
            assertEquals(3L, store.publishSnapshot(data.world(), owner, 2L, upload, hash, candidate));
        }
    }

}
