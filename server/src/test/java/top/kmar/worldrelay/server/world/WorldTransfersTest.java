package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.auth.TransferTestUsers;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static top.kmar.worldrelay.server.world.WorldStore.StopStage.BEGIN;

class WorldTransfersTest {

    @TempDir
    Path root;

    private SshRegistrationConfig ssh() {
        return new SshRegistrationConfig(root.resolve("ssh/authorized_keys"), root.resolve("transfers"),
            root.resolve("no-rrsync-needed-in-unit-tests").toString(), "work", root.resolve("host.pub"));
    }

    private UserStore users() throws IOException {
        var users = TransferTestUsers.open(root.resolve("users.json"), ssh());
        if (!Files.exists(root.resolve("host.pub"))) {
            var alice = users.register("Alice", false);
            assertNotNull(alice);
            Files.writeString(root.resolve("host.pub"), TransferTestUsers.hostPublicKey());
            assertNotNull(users.register("Bob", false));
        }
        return users;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private WorldStore.Identity acquire(WorldStore worlds, String world, String user) throws Exception {
        String device = id();
        var result = worlds.acquire(world, user, device, id());
        return new WorldStore.Identity(user, device, result.view().sessionId(), result.view().epoch());
    }

    private String create(WorldStore worlds) throws IOException {
        var world = worlds.create("增量接力测试");
        assertNotNull(world);
        return world.worldId();
    }

    private Path payload(String world, WorldStore.Identity owner, String upload) {
        return ssh().transferDirectory().resolve(".sessions")
            .resolve(SnapshotManifest.hash(owner.username().getBytes(StandardCharsets.UTF_8)))
            .resolve(world).resolve(Long.toString(owner.epoch())).resolve("upload-" + upload).resolve("payload");
    }

    private String manifest(Path payload) throws IOException {
        byte[] bytes = SnapshotFiles.scan(payload.resolve("world")).encode();
        Files.write(payload.resolve("manifest.bin"), bytes);
        return SnapshotManifest.hash(bytes);
    }

    private void firstSave(Path payload) throws IOException {
        Path world = payload.resolve("world");
        Files.writeString(world.resolve("level.dat"), "test-level-data");
        Files.createDirectories(world.resolve("playerdata"));
        Files.writeString(world.resolve("playerdata/player.dat"), "inventory-end-chest-position-xp");
        Files.createDirectories(world.resolve("dimensions/mod/moon/region"));
        Files.writeString(world.resolve("dimensions/mod/moon/region/r.0.0.mca"), "blocks-and-entities");
        Files.createDirectories(world.resolve("unknown-mod/empty"));
        Files.writeString(world.resolve("unknown-mod/train.dat"), "train-one");
    }

    /** 复制前空间不足保留所有权和暂存数据，补足空间后可用同一上传身份重试完成提交。 */
    @Test
    void retriesUploadAfterStorageBecomesAvailable() throws Exception {
        var available = new java.util.concurrent.atomic.AtomicLong(0);
        try (var users = users(); var worlds = WorldStore.open(root.resolve("worlds"));
             var retention = new WorldRetention(worlds, ssh().transferDirectory(),
                 new RetentionConfig(5, 7, 24, 1, 60, java.time.ZoneOffset.UTC),
                 java.time.Clock.systemUTC(), ignored -> available.get())) {
            var transfers = new WorldTransfers(worlds, users, ssh(), retention);
            String world = create(worlds), upload = id();
            var owner = acquire(worlds, world, "Alice");
            worlds.stop(world, owner, BEGIN, null);
            assertEquals("INSUFFICIENT_STORAGE", assertThrows(WorldAccessException.class,
                () -> transfers.begin(world, owner, null, upload, true)).code());
            available.set(Long.MAX_VALUE);
            transfers.begin(world, owner, null, upload, true);
            Path payload = payload(world, owner, upload);
            firstSave(payload);
            String hash = manifest(payload);
            available.set(0);
            assertEquals("INSUFFICIENT_STORAGE", assertThrows(WorldAccessException.class,
                () -> transfers.commit(world, owner, null, upload, hash)).code());
            assertEquals("COMMITTING", worlds.heartbeat(world, owner, null).status());
            assertEquals("train-one", Files.readString(payload.resolve("world/unknown-mod/train.dat")));
            available.set(Long.MAX_VALUE);
            assertEquals(1, transfers.commit(world, owner, null, upload, hash));
            assertEquals("FREE", worlds.list().getFirst().status());
        }
    }

    /** 验证交接使用旧版本作为增量基础，保留历史快照并支持提交幂等重试。 */
    @Test
    void fullHandoverSeedsOldDataPreservesHistoryAndRetriesLostCommitResponse() throws Exception {
        String world, upload1 = id(), hash1, upload2 = id(), hash2;
        WorldStore.Identity alice, bob;
        try (var users = users(); var worlds = WorldStore.open(root.resolve("worlds"))) {
            var transfers = new WorldTransfers(worlds, users, ssh());
            world = create(worlds);
            alice = acquire(worlds, world, "Alice");
            worlds.stop(world, alice, BEGIN, null);
            var ticket = transfers.begin(world, alice, null, upload1, true);
            assertFalse(ticket.toString().contains("BEGIN OPENSSH"));
            assertTrue(Files.readString(ssh().authorizedKeysFile()).contains(" -wo "));
            Path upload = payload(world, alice, upload1);
            firstSave(upload);
            hash1 = manifest(upload);
            assertEquals(1, transfers.commit(world, alice, null, upload1, hash1));
            assertEquals("FREE", worlds.list().getFirst().status());
            assertFalse(Files.readString(ssh().authorizedKeysFile()).contains(" worldrelay-transfer:"));
            Path first = root.resolve("worlds").resolve(world).resolve("snapshots").resolve(upload1);
            Files.writeString(upload.resolve("world/level.dat"), "late-old-ssh-writer");
            assertEquals("test-level-data", Files.readString(first.resolve("world/level.dat")));

            bob = acquire(worlds, world, "Bob");
            var view = worlds.heartbeat(world, bob, null);
            assertEquals(1L, view.baseVersion());
            assertTrue(bob.epoch() > alice.epoch());
            worlds.stop(world, bob, BEGIN, 1L);
            transfers.begin(world, bob, 1L, upload2, true);
            Path seeded = payload(world, bob, upload2);
            // 增量传输的基础是完整旧数据，且不是与权威版本共享的硬链接。
            assertEquals("train-one", Files.readString(seeded.resolve("world/unknown-mod/train.dat")));
            Files.writeString(seeded.resolve("world/unknown-mod/train.dat"), "train-two");
            Files.delete(seeded.resolve("world/dimensions/mod/moon/region/r.0.0.mca"));
            hash2 = manifest(seeded);
            assertEquals(2, transfers.commit(world, bob, 1L, upload2, hash2));
            assertEquals("train-one", Files.readString(first.resolve("world/unknown-mod/train.dat")));
            assertTrue(Files.exists(first.resolve("world/dimensions/mod/moon/region/r.0.0.mca")));
            var next = acquire(worlds, world, "Alice");
            assertEquals(2L, worlds.heartbeat(world, next, null).committedVersion());
            // Bob 重试丢失的成功响应不能撤销 Alice 的新任期。
            assertEquals(2, transfers.commit(world, bob, 1L, upload2, hash2));
            assertEquals(next.sessionId(), worlds.heartbeat(world, next, null).sessionId());
        }
        try (var worlds = WorldStore.open(root.resolve("worlds"))) {
            assertNotNull(worlds.list().getFirst().lastCommittedAt());
            assertEquals(2L, worlds.committedResult(world, bob, upload2, hash2, 1L));
            assertEquals(1L, worlds.committedResult(world, alice, upload1, hash1, null));
            try (var snapshots = Files.list(root.resolve("worlds").resolve(world).resolve("snapshots"))) {
                assertEquals(2, snapshots.count());
            }
        }
    }

    /** 验证摘要错误、身份错误、旧任期和错误基础版本均被拒绝，且不释放所有权。 */
    @Test
    void incompleteUploadDigestMismatchAndWrongIdentityNeverReleaseOwnership() throws Exception {
        try (var users = users(); var worlds = WorldStore.open(root.resolve("worlds"))) {
            var transfers = new WorldTransfers(worlds, users, ssh());
            String world = create(worlds), upload = id();
            var alice = acquire(worlds, world, "Alice");
            assertThrows(WorldAccessException.class, () -> transfers.begin(world, alice, null, upload, true));
            worlds.stop(world, alice, BEGIN, null);
            transfers.begin(world, alice, null, upload, true);
            Path payload = payload(world, alice, upload);
            firstSave(payload);
            String hash = manifest(payload);
            Files.writeString(payload.resolve("world/unknown-mod/train.dat"), "train-two"); // 长度相同，mtime 不是验证依据。
            assertThrows(IOException.class, () -> transfers.commit(world, alice, null, upload, hash));
            assertEquals("COMMITTING", worlds.heartbeat(world, alice, null).status());
            assertThrows(WorldAccessException.class, () -> acquire(worlds, world, "Bob"));
            var wrong = new WorldStore.Identity("Bob", alice.deviceId(), alice.sessionId(), alice.epoch());
            assertThrows(WorldAccessException.class, () -> transfers.commit(world, wrong, null, upload, hash));
            var stale = new WorldStore.Identity("Alice", alice.deviceId(), alice.sessionId(), alice.epoch() + 1);
            assertThrows(WorldAccessException.class, () -> transfers.begin(world, stale, null, upload, true));
            assertThrows(WorldAccessException.class, () -> transfers.begin(world, alice, 9L, upload, true));
            assertNull(transfers.status(world, alice, null, upload, hash));
            String repaired = manifest(payload);
            assertEquals(1, transfers.commit(world, alice, null, upload, repaired));
            assertEquals("COMMIT_CONFLICT", assertThrows(WorldAccessException.class,
                () -> transfers.commit(world, alice, null, upload, hash)).code());
        }
    }

    /** 模拟快照落盘后元数据发布失败，验证重启后仍可持有所有权并重试提交。 */
    @Test
    void restartAfterSnapshotRenameBeforeMetadataPublicationIsRecoverable() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        String world, upload = id(), hash;
        WorldStore.Identity owner;
        try (var users = users(); var worlds = WorldStore.open(root.resolve("worlds"), WorldTransfersTest::move, UUID::randomUUID,
            (source, target) -> {
                if (fail.getAndSet(false)) throw new IOException("simulated crash");
                move(source, target);
            }, System::nanoTime, 10, 30)) {
            world = create(worlds);
            owner = acquire(worlds, world, "Alice");
            worlds.stop(world, owner, BEGIN, null);
            var transfers = new WorldTransfers(worlds, users, ssh());
            transfers.begin(world, owner, null, upload, true);
            Path payload = payload(world, owner, upload);
            firstSave(payload);
            hash = manifest(payload);
            fail.set(true);
            assertThrows(IOException.class, () -> transfers.commit(world, owner, null, upload, hash));
            assertEquals("RECOVERY_REQUIRED", worlds.list().getFirst().status());
        }
        try (var users = users(); var worlds = WorldStore.open(root.resolve("worlds"))) {
            assertEquals(WorldPhase.COMMITTING, worlds.heartbeat(world, owner, null).phase());
            assertEquals(1, new WorldTransfers(worlds, users, ssh()).commit(world, owner, null, upload, hash));
            assertEquals("FREE", worlds.list().getFirst().status());
        }
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

}
