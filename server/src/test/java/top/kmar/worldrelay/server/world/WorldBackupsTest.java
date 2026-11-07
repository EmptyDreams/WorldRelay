package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorldBackupsTest {

    @TempDir
    Path root;

    private WorldRetention retention(WorldStore store) {
        return new WorldRetention(store, root.resolve("transfers"), new RetentionConfig(5, 7, 24, 0, 60, ZoneOffset.UTC),
            Clock.systemUTC(), ignored -> Long.MAX_VALUE);
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 恢复完整历史快照为递增的新版本，保留原副本、审计来源，重启及后续接管后重试不重复恢复。 */
    @Test
    void restoresAsNewVersionAndRetainsAuditAndIdempotencyAcrossRestart() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        WorldBackups.Restore request;
        WorldBackups.Result result;
        try (var store = WorldStore.open(data.root()); var retention = retention(store)) {
            var backups = new WorldBackups(store, retention);
            assertTrue(backups.list(data.world()).contains("\"currentVersion\":2"));
            request = backups.preview(data.world(), 1);
            result = backups.restore(request);
            assertEquals(3, result.version());
            assertEquals(3, result.epoch());
            assertEquals(2, result.previousVersion());
            assertEquals("first-world", Files.readString(data.snapshot(result.restoreId()).resolve("world/level.dat")));
            assertEquals("first-world-train", Files.readString(data.snapshot(result.restoreId()).resolve("world/mod-data/train.dat")));
            assertTrue(Files.isDirectory(data.snapshot(result.restoreId()).resolve("world/mod-data/empty")));
            assertEquals(result, backups.restore(request));
            var metadata = WorldMetadata.parse(Files.readString(data.metadata()));
            assertEquals(1L, metadata.commits().getLast().restoredFromVersion());
            assertEquals("local-admin", metadata.commits().getLast().owner().username());
            Files.writeString(data.snapshot(data.first()).resolve("world/level.dat"), "later-source-writer");
            assertEquals("first-world", Files.readString(data.snapshot(result.restoreId()).resolve("world/level.dat")));
        }
        try (var store = WorldStore.open(data.root()); var retention = retention(store)) {
            var nextOwner = BackupTestWorld.acquire(store, data.world());
            assertEquals(4, nextOwner.epoch());
            var source = store.prepareTransfer(data.world(), nextOwner, 3L, false);
            assertNotNull(source);
            assertEquals(data.snapshot(result.restoreId()), source.directory());
            assertEquals(result, new WorldBackups(store, retention).restore(request));
            assertEquals(nextOwner.epoch(), store.heartbeat(data.world(), nextOwner, null).epoch());
        }
    }

    /** 托管中及离线持有者不能被恢复操作绕过，预览后版本或任期改变必须重新预览。 */
    @Test
    void refusesOwnedWorldsAndStaleRestorePreviews() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        WorldBackups.Restore request;
        WorldStore.Identity owner;
        try (var store = WorldStore.open(data.root()); var retention = retention(store)) {
            var backups = new WorldBackups(store, retention);
            request = backups.preview(data.world(), 1);
            owner = BackupTestWorld.acquire(store, data.world());
            assertEquals("WORLD_NOT_FREE", assertThrows(WorldAccessException.class, () -> backups.restore(request)).code());
            assertEquals("WORLD_NOT_FREE", assertThrows(WorldAccessException.class, () -> backups.preview(data.world(), 1)).code());
            assertTrue(backups.list(data.world()).contains("\"canRestore\":false"));
        }
        try (var store = WorldStore.open(data.root()); var retention = retention(store)) {
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
            var backups = new WorldBackups(store, retention);
            assertEquals("WORLD_NOT_FREE", assertThrows(WorldAccessException.class, () -> backups.restore(request)).code());
            store.stop(data.world(), owner, WorldStore.StopStage.BEGIN, 2L);
            store.prepareTransfer(data.world(), owner, 2L, true);
            Path candidate = store.candidateDirectory(data.world(), owner).resolve("payload");
            top.kmar.worldrelay.snapshot.SnapshotFiles.copyIsolated(data.snapshot(data.second()), candidate, false);
            var current = WorldMetadata.parse(Files.readString(data.metadata())).commits().getLast();
            store.publishSnapshot(data.world(), owner, 2L, BackupTestWorld.id(), current.manifestHash(), candidate);
            assertEquals("WORLD_CHANGED", assertThrows(WorldAccessException.class, () -> backups.restore(request)).code());
        }
    }

    /** 恢复复制期间阻止获取所有权，并保护源备份免遭常规保留策略删除；中止后继续使用原权威版本。 */
    @Test
    void pinsSourceAndReservesWorldDuringRestore() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        try (var store = WorldStore.open(data.root()); var retention = new WorldRetention(store, root.resolve("transfers"),
            new RetentionConfig(1, 0, 24, 0, 60, ZoneOffset.UTC), Clock.systemUTC(), ignored -> Long.MAX_VALUE)) {
            var backups = new WorldBackups(store, retention);
            var request = backups.preview(data.world(), 1);
            assertTrue(store.beginRestore(request, data.first()));
            assertEquals("COMMITTING", store.list().getFirst().status());
            assertEquals("RESTORE_IN_PROGRESS", assertThrows(WorldAccessException.class,
                () -> BackupTestWorld.acquire(store, data.world())).code());
            retention.sweep();
            assertTrue(Files.exists(data.snapshot(data.first())));
            store.endRestore(request);
            var owner = BackupTestWorld.acquire(store, data.world());
            assertEquals(2L, store.heartbeat(data.world(), owner, null).committedVersion());
        }
    }

    /** 备份校验失败时保留原权威指针并解除本次复制互斥，修复文件后可重试相同恢复请求。 */
    @Test
    void checksumFailureLeavesAuthorityIntactAndAllowsRetry() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        try (var store = WorldStore.open(data.root()); var retention = retention(store)) {
            var backups = new WorldBackups(store, retention);
            var request = backups.preview(data.world(), 1);
            Path file = data.snapshot(data.first()).resolve("world/mod-data/train.dat");
            Files.writeString(file, "corrupt");
            assertThrows(IOException.class, () -> backups.restore(request));
            assertEquals("FREE", store.list().getFirst().status());
            assertEquals(2L, WorldMetadata.parse(Files.readString(data.metadata())).state().committedVersion());
            Files.writeString(file, "first-world-train");
            assertEquals(3, backups.restore(request).version());
        }
    }

    /** 提交回执已淘汰的备份仍可通过快照索引恢复，并验证原始清单摘要。 */
    @Test
    void restoresBackupWhoseCommitReceiptWasEvicted() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        var metadata = WorldMetadata.parse(Files.readString(data.metadata()));
        Path index = Files.createDirectory(data.directory().resolve("snapshot-index"));
        var first = metadata.commits().getFirst();
        Files.writeString(index.resolve(first.uploadId() + ".json"), SnapshotReference.from(first).toJson());
        Files.writeString(data.metadata(), metadata.withCommits(List.of(metadata.commits().getLast())).toJson());
        try (var store = WorldStore.open(data.root()); var retention = retention(store)) {
            var backups = new WorldBackups(store, retention);
            var request = backups.preview(data.world(), 1);
            assertEquals(first.manifestHash(), request.manifestHash());
            assertEquals(3, backups.restore(request).version());
        }
    }

    /** 同时确认两个恢复操作最多发布一个新版本，另一请求不能覆盖获胜结果。 */
    @Test
    void concurrentRestoresPublishOnlyOnce() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        try (var store = WorldStore.open(data.root()); var retention = retention(store); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var backups = new WorldBackups(store, retention);
            var a = backups.preview(data.world(), 1);
            var b = backups.preview(data.world(), 2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = java.util.stream.Stream.of(a, b).map(request -> executor.submit(() -> {
                start.await();
                try {
                    backups.restore(request);
                    return true;
                } catch (WorldAccessException conflict) {
                    return false;
                }
            })).toList();
            start.countDown();
            int published = 0;
            for (var task : tasks) {
                if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) published++;
            }
            assertEquals(1, published);
            assertEquals(3L, WorldMetadata.parse(Files.readString(data.metadata())).state().committedVersion());
        }
    }

    /** 元数据发布前后中断均按磁盘结果恢复；重试相同操作不会产生额外版本。 */
    @Test
    void recoversFromUncertainPublicationWithoutRepeatingRestore() throws Exception {
        for (boolean moved : List.of(false, true)) {
            var data = BackupTestWorld.create(root.resolve(Boolean.toString(moved)));
            var fail = new AtomicBoolean(true);
            WorldBackups.Restore request;
            try (var store = WorldStore.open(data.root(), WorldBackupsTest::move, UUID::randomUUID, (source, target) -> {
                if (fail.getAndSet(false)) {
                    if (moved) move(source, target);
                    throw new IOException("uncertain restore publication");
                }
                move(source, target);
            }, System::nanoTime, 10, 30); var retention = retention(store)) {
                var backups = new WorldBackups(store, retention);
                request = backups.preview(data.world(), 1);
                assertThrows(IOException.class, () -> backups.restore(request));
                assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
            }
            try (var store = WorldStore.open(data.root()); var retention = retention(store)) {
                var backups = new WorldBackups(store, retention);
                assertEquals(3, backups.restore(request).version());
                assertEquals(3, backups.restore(request).version());
            }
        }
    }

}