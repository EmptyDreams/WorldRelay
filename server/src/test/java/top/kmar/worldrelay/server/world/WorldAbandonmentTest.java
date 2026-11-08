package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorldAbandonmentTest {

    @TempDir
    Path root;

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 显式放弃离线持有者只撤销任期，保留最后快照与未提交候选，并在新房主接手及重启后保持幂等。 */
    @Test
    void abandonsOfflineProgressWithoutChangingCommittedDataOrLaterOwnership() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        WorldStore.Identity old;
        Path candidate;
        try (var store = WorldStore.open(data.root())) {
            old = BackupTestWorld.acquire(store, data.world());
            store.stop(data.world(), old, WorldStore.StopStage.BEGIN, 2L);
            store.prepareTransfer(data.world(), old, 2L, true);
            candidate = store.candidateDirectory(data.world(), old).resolve("payload");
            SnapshotFiles.copyIsolated(data.snapshot(data.second()), candidate, false);
            Files.writeString(candidate.resolve("world/level.dat"), "unuploaded-progress");
        }
        WorldAbandonment.Request request;
        WorldAbandonment.Result result;
        WorldStore.Identity next;
        try (var store = WorldStore.open(data.root())) {
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
            var preview = store.previewAbandon(data.world());
            assertEquals(2L, preview.expectedVersion());
            request = new WorldAbandonment.Request(preview.worldId(), preview.abandonId(), preview.expectedEpoch(), preview.expectedVersion(),
                preview.expectedPhase(), preview.ownerUsername(), preview.lastCommittedAt(), "管理员确认放弃未上传进度");
            result = store.abandon(request);
            assertEquals(old.epoch() + 1, result.epoch());
            assertEquals(2L, result.committedVersion());
            assertEquals("FREE", store.list().getFirst().status());
            assertEquals("second-world", Files.readString(data.snapshot(data.second()).resolve("world/level.dat")));
            assertEquals("unuploaded-progress", Files.readString(candidate.resolve("world/level.dat")));
            assertEquals("STALE_EPOCH", assertThrows(WorldAccessException.class, () -> store.heartbeat(data.world(), old, null)).code());
            assertThrows(WorldAccessException.class, () -> store.publishSnapshot(data.world(), old, 2L, BackupTestWorld.id(), "a".repeat(64), candidate));
            var metadata = WorldMetadata.parse(Files.readString(data.metadata()));
            var audit = metadata.cancellations().getLast();
            assertEquals(old.sessionId(), audit.owner().sessionId());
            assertEquals(request.reason(), audit.reason());
            assertEquals(request.abandonId(), audit.abandonId());
            next = BackupTestWorld.acquire(store, data.world());
            assertEquals(old.epoch() + 2, next.epoch());
            assertEquals(result, store.abandon(request));
            assertEquals(next.epoch(), store.heartbeat(data.world(), next, null).epoch());
            assertEquals("ACQUISITION_CANCELLED", assertThrows(WorldAccessException.class,
                () -> store.acquire(data.world(), request.ownerUsername(), audit.owner().deviceId(), audit.owner().requestId())).code());
            var changed = new WorldAbandonment.Request(request.worldId(), request.abandonId(), request.expectedEpoch(), request.expectedVersion(),
                request.expectedPhase(), request.ownerUsername(), request.lastCommittedAt(), "different reason");
            assertEquals("ABANDON_CONFLICT", assertThrows(WorldAccessException.class, () -> store.abandon(changed)).code());
        }
        try (var store = WorldStore.open(data.root())) {
            assertEquals(result, store.abandon(request));
            assertEquals(next.epoch(), store.heartbeat(data.world(), next, null).epoch());
        }
    }

    /** 首次生成未提交的世界可明确放弃为空世界，空回执的审计格式及后续普通取消仍能持久化读取。 */
    @Test
    void abandonsUncommittedCreationAndPreservesAuditThroughLaterCancellation() throws Exception {
        String world;
        WorldAbandonment.Request request;
        try (var store = WorldStore.open(root)) {
            var created = store.create("首次生成");
            assertNotNull(created);
            world = created.worldId();
            var owner = BackupTestWorld.acquire(store, world);
            store.ready(world, owner, WorldStore.ReadyStage.SAVE_SYNCED, null, null);
            request = store.previewAbandon(world);
            assertNull(request.expectedVersion());
            assertNull(request.lastCommittedAt());
            var result = store.abandon(request);
            assertNull(result.committedVersion());
            assertEquals(2, result.epoch());
            assertEquals("FREE", store.list().getFirst().status());
            var next = BackupTestWorld.acquire(store, world);
            assertNull(store.heartbeat(world, next, null).committedVersion());
            store.cancelCreation(world, next);
        }
        try (var store = WorldStore.open(root)) {
            assertEquals(2, store.abandon(request).epoch());
            var metadata = WorldMetadata.parse(Files.readString(root.resolve(world).resolve("world.json")));
            assertEquals(2, metadata.cancellations().size());
            assertTrue(metadata.commits().isEmpty());
        }
    }

    /** 预览后阶段或持有者变化会使请求失效，不能误撤销新的上传流程或下一任房主。 */
    @Test
    void rejectsPreviewAfterPhaseOrOwnershipChanges() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        try (var store = WorldStore.open(data.root())) {
            var owner = BackupTestWorld.acquire(store, data.world());
            var request = store.previewAbandon(data.world());
            store.stop(data.world(), owner, WorldStore.StopStage.BEGIN, 2L);
            assertEquals("WORLD_CHANGED", assertThrows(WorldAccessException.class, () -> store.abandon(request)).code());
            store.prepareTransfer(data.world(), owner, 2L, true);
            Path candidate = store.candidateDirectory(data.world(), owner).resolve("payload");
            SnapshotFiles.copyIsolated(data.snapshot(data.second()), candidate, false);
            String hash = WorldMetadata.parse(Files.readString(data.metadata())).commits().getLast().manifestHash();
            store.publishSnapshot(data.world(), owner, 2L, BackupTestWorld.id(), hash, candidate);
            assertEquals("WORLD_NOT_OWNED", assertThrows(WorldAccessException.class, () -> store.previewAbandon(data.world())).code());
            var next = BackupTestWorld.acquire(store, data.world());
            assertEquals("WORLD_CHANGED", assertThrows(WorldAccessException.class, () -> store.abandon(request)).code());
            assertEquals(next.epoch(), store.heartbeat(data.world(), next, null).epoch());
        }
    }

    /** 正常提交与管理员放弃并发时只有一个能成功，最终权威版本和任期对应同一原子结果。 */
    @Test
    void serializesAbandonmentAgainstSnapshotPublication() throws Exception {
        var data = BackupTestWorld.create(root.resolve("worlds"));
        try (var store = WorldStore.open(data.root()); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = BackupTestWorld.acquire(store, data.world());
            store.stop(data.world(), owner, WorldStore.StopStage.BEGIN, 2L);
            store.prepareTransfer(data.world(), owner, 2L, true);
            Path candidate = store.candidateDirectory(data.world(), owner).resolve("payload");
            SnapshotFiles.copyIsolated(data.snapshot(data.second()), candidate, false);
            String hash = WorldMetadata.parse(Files.readString(data.metadata())).commits().getLast().manifestHash();
            var request = store.previewAbandon(data.world());
            var start = new CountDownLatch(1);
            var committing = executor.submit(() -> {
                start.await();
                try {
                    store.publishSnapshot(data.world(), owner, 2L, BackupTestWorld.id(), hash, candidate);
                    return true;
                } catch (WorldAccessException conflict) {
                    return false;
                }
            });
            var abandoning = executor.submit(() -> {
                start.await();
                try {
                    store.abandon(request);
                    return true;
                } catch (WorldAccessException conflict) {
                    return false;
                }
            });
            start.countDown();
            boolean published = committing.get(10, TimeUnit.SECONDS), abandoned = abandoning.get(10, TimeUnit.SECONDS);
            assertNotEquals(published, abandoned);
            var metadata = WorldMetadata.parse(Files.readString(data.metadata()));
            assertEquals(published ? 3L : 2L, metadata.state().committedVersion());
            assertEquals(abandoned ? owner.epoch() + 1 : owner.epoch(), metadata.state().epoch());
            assertEquals("FREE", store.list().getFirst().status());
        }
    }

    /** 放弃审计发布结果不确定时停止修改世界，重启后可用原操作 ID 安全确认或重试一次。 */
    @Test
    void retriesUncertainAbandonmentAfterRestartWithoutRevokingAgain() throws Exception {
        for (boolean moved : List.of(false, true)) {
            var data = BackupTestWorld.create(root.resolve(Boolean.toString(moved)));
            var fail = new AtomicBoolean();
            WorldAbandonment.Request request;
            try (var store = WorldStore.open(data.root(), WorldAbandonmentTest::move, UUID::randomUUID, (source, target) -> {
                if (fail.getAndSet(false)) {
                    if (moved) move(source, target);
                    throw new IOException("uncertain abandonment publication");
                }
                move(source, target);
            }, System::nanoTime, 10, 30)) {
                BackupTestWorld.acquire(store, data.world());
                request = store.previewAbandon(data.world());
                fail.set(true);
                assertThrows(IOException.class, () -> store.abandon(request));
                assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
                assertThrows(WorldAccessException.class, () -> BackupTestWorld.acquire(store, data.world()));
            }
            try (var store = WorldStore.open(data.root())) {
                var result = store.abandon(request);
                assertEquals(request.expectedEpoch() + 1, result.epoch());
                assertEquals(result, store.abandon(request));
                var next = BackupTestWorld.acquire(store, data.world());
                assertEquals(result, store.abandon(request));
                assertEquals(next.epoch(), store.heartbeat(data.world(), next, null).epoch());
            }
        }
    }

}