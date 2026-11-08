package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.kmar.worldrelay.server.protocol.HostEndpoint;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static top.kmar.worldrelay.server.world.WorldStore.ReadyStage.*;

class WorldRecoveryTest {

    @TempDir
    Path directory;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    /** 重启后原房主可从准备、启动、运行或停止阶段恢复；沿用任期及版本，重新开端口后正常提交交接。 */
    @ParameterizedTest
    @EnumSource(value = WorldPhase.class, names = {"PREPARING", "STARTING", "RUNNING", "STOPPING"})
    void resumesInterruptedOwnershipAndCommitsNormally(WorldPhase phase) throws Exception {
        var fixture = BackupTestWorld.create(directory);
        WorldStore.Identity owner;
        try (var store = WorldStore.open(directory)) {
            owner = BackupTestWorld.acquire(store, fixture.world());
            if (phase != WorldPhase.PREPARING) store.ready(fixture.world(), owner, SAVE_SYNCED, 2L, null);
            if (phase == WorldPhase.RUNNING) store.ready(fixture.world(), owner, PORT_OPENED, null, new HostEndpoint("127.0.0.1", 25565));
            if (phase == WorldPhase.STOPPING) store.stop(fixture.world(), owner, WorldStore.StopStage.BEGIN, null);
        }
        try (var store = WorldStore.open(directory)) {
            var recovered = store.ready(fixture.world(), owner, RESUME, 2L, null);
            assertEquals(WorldPhase.STARTING, recovered.phase());
            assertEquals(owner.epoch(), recovered.epoch());
            assertEquals(owner.sessionId(), recovered.sessionId());
            assertEquals(2L, recovered.committedVersion());
            assertNull(recovered.endpoint());
            assertEquals(recovered, store.ready(fixture.world(), owner, RESUME, 2L, null));
            assertEquals("WORLD_OWNED", assertThrows(WorldAccessException.class,
                () -> store.acquire(fixture.world(), "Other", id(), id())).code());
            var endpoint = new HostEndpoint("127.0.0.1", 25566);
            assertEquals(endpoint, store.ready(fixture.world(), owner, PORT_OPENED, null, endpoint).endpoint());
            store.stop(fixture.world(), owner, WorldStore.StopStage.BEGIN, null);
            store.prepareTransfer(fixture.world(), owner, 2L, true);
            Path candidate = store.candidateDirectory(fixture.world(), owner).resolve("payload");
            Files.createDirectories(candidate.resolve("world"));
            Files.writeString(candidate.resolve("world/level.dat"), "recovered-progress");
            byte[] manifest = SnapshotFiles.scan(candidate.resolve("world")).encode();
            Files.write(candidate.resolve("manifest.bin"), manifest);
            assertEquals(3, store.publishSnapshot(fixture.world(), owner, 2L, id(), SnapshotManifest.hash(manifest), candidate));
            assertEquals("FREE", store.list().getFirst().status());
            assertEquals("second-world", Files.readString(fixture.snapshot(fixture.second()).resolve("world/level.dat")));
        }
    }

    /** 恢复必须核对持有者、设备、会话、任期和基础版本；上传开始后继续沿用提交重试流程。 */
    @Test
    void rejectsInvalidOwnershipAndInFlightCommit() throws Exception {
        var fixture = BackupTestWorld.create(directory);
        try (var store = WorldStore.open(directory)) {
            var owner = BackupTestWorld.acquire(store, fixture.world());
            for (var wrong : new WorldStore.Identity[]{
                new WorldStore.Identity("Other", owner.deviceId(), owner.sessionId(), owner.epoch()),
                new WorldStore.Identity(owner.username(), id(), owner.sessionId(), owner.epoch()),
                new WorldStore.Identity(owner.username(), owner.deviceId(), id(), owner.epoch()),
                new WorldStore.Identity(owner.username(), owner.deviceId(), owner.sessionId(), owner.epoch() - 1)}) {
                assertThrows(WorldAccessException.class, () -> store.ready(fixture.world(), wrong, RESUME, 2L, null));
            }
            assertEquals("BASE_VERSION_MISMATCH", assertThrows(WorldAccessException.class,
                () -> store.ready(fixture.world(), owner, RESUME, 1L, null)).code());
            store.stop(fixture.world(), owner, WorldStore.StopStage.BEGIN, null);
            store.prepareTransfer(fixture.world(), owner, 2L, true);
            assertEquals("INVALID_PHASE", assertThrows(WorldAccessException.class,
                () -> store.ready(fixture.world(), owner, RESUME, 2L, null)).code());
            assertEquals(WorldPhase.COMMITTING, store.heartbeat(fixture.world(), owner, null).phase());
        }
    }

}
