package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class LocalReuseTest {

    @TempDir
    Path root;

    private record Lost(BackupTestWorld data, WorldStore.Identity owner) {

    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private Lost lost() throws Exception {
        var data = BackupTestWorld.create(root);
        try (var store = WorldStore.open(root)) {
            String device = id();
            var view = store.acquire(data.world(), "OldHost", device, id()).view();
            var owner = new WorldStore.Identity("OldHost", device, view.sessionId(), view.epoch());
            store.abandon(store.previewAbandon(data.world()));
            return new Lost(data, owner);
        }
    }

    /** 仅原持有者紧接撤销后、权威版本未变化时可恢复本地来源，获取响应丢失和重启仍复用同一新任期。 */
    @Test
    void reacquiresImmediatelyRevokedSourceAndRetriesAcrossRestart() throws Exception {
        var lost = lost();
        var owner = lost.owner();
        String request = id();
        String session;
        try (var store = WorldStore.open(root)) {
            assertTrue(store.canReuseLocal(lost.data().world(), owner.username(), owner.deviceId(), owner.epoch(), 2L));
            assertFalse(store.canReuseLocal(lost.data().world(), "Other", owner.deviceId(), owner.epoch(), 2L));
            assertFalse(store.canReuseLocal(lost.data().world(), owner.username(), id(), owner.epoch(), 2L));
            assertFalse(store.canReuseLocal(lost.data().world(), owner.username(), owner.deviceId(), owner.epoch(), 1L));
            var acquired = store.acquire(lost.data().world(), owner.username(), owner.deviceId(), request, owner.epoch(), 2L);
            assertTrue(acquired.acquired());
            assertEquals(owner.epoch() + 2, acquired.view().epoch());
            assertEquals(2L, acquired.view().baseVersion());
            session = acquired.view().sessionId();
            assertFalse(store.acquire(lost.data().world(), owner.username(), owner.deviceId(), request, owner.epoch(), 2L).acquired());
        }
        try (var store = WorldStore.open(root)) {
            var retry = store.acquire(lost.data().world(), owner.username(), owner.deviceId(), request, owner.epoch(), 2L);
            assertFalse(retry.acquired());
            assertEquals(session, retry.view().sessionId());
            assertEquals(owner.epoch() + 2, retry.view().epoch());
        }
    }

    /** 其他玩家只获取后放弃、或确实上传过，两种情况都使旧本地来源失效，拒绝时不占锁也不改权威存档。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsInterveningHostingEvenWithoutACommit(boolean uploaded) throws Exception {
        var lost = lost();
        var owner = lost.owner();
        try (var store = WorldStore.open(root)) {
            if (uploaded) BackupTestWorld.save(store, lost.data().world(), "other-progress");
            else {
                store.acquire(lost.data().world(), "Other", id(), id());
                store.abandon(store.previewAbandon(lost.data().world()));
            }
            String before = Files.readString(lost.data().metadata());
            assertFalse(store.canReuseLocal(lost.data().world(), owner.username(), owner.deviceId(), owner.epoch(), 2L));
            assertEquals("LOCAL_REUSE_NOT_ALLOWED", assertThrows(WorldAccessException.class,
                () -> store.acquire(lost.data().world(), owner.username(), owner.deviceId(), id(), owner.epoch(), 2L)).code());
            assertEquals(before, Files.readString(lost.data().metadata()));
            assertEquals("FREE", store.list().getFirst().status());
            var fresh = store.acquire(lost.data().world(), owner.username(), owner.deviceId(), id()).view();
            assertEquals(uploaded ? 3L : 2L, fresh.baseVersion());
            assertEquals("second-world", Files.readString(lost.data().snapshot(lost.data().second()).resolve("world/level.dat")));
        }
    }

    /** 预览后与其他玩家并发获取时，服务端原子复核来源条件，最终只能授予一份可写所有权。 */
    @Test
    void serializesGuardedReuseAgainstAnotherAcquisition() throws Exception {
        var lost = lost();
        var owner = lost.owner();
        try (var store = WorldStore.open(root); var jobs = Executors.newVirtualThreadPerTaskExecutor()) {
            assertTrue(store.canReuseLocal(lost.data().world(), owner.username(), owner.deviceId(), owner.epoch(), 2L));
            var start = new CountDownLatch(1);
            var local = jobs.submit(() -> {
                start.await();
                try {
                    store.acquire(lost.data().world(), owner.username(), owner.deviceId(), id(), owner.epoch(), 2L);
                    return true;
                } catch (WorldAccessException conflict) {
                    return false;
                }
            });
            var other = jobs.submit(() -> {
                start.await();
                try {
                    store.acquire(lost.data().world(), "Other", id(), id());
                    return true;
                } catch (WorldAccessException conflict) {
                    return false;
                }
            });
            start.countDown();
            assertNotEquals(local.get(), other.get());
            assertNotEquals("FREE", store.list().getFirst().status());
        }
    }

}