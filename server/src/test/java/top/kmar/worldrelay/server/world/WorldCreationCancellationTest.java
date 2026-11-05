package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorldCreationCancellationTest {

    @TempDir
    Path directory;
    private final String device = id(), request = id();

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private WorldStore.Identity identity(top.kmar.worldrelay.server.protocol.OwnershipView view) {
        return new WorldStore.Identity("房主", device, view.sessionId(), view.epoch());
    }

    private static void code(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(WorldAccessException.class, operation).code());
    }

    private static String createWorld(WorldStore store, String name) throws IOException {
        var created = store.create(name);
        assertNotNull(created, "测试前置条件：世界应成功创建，不能与已有世界重名");
        return created.worldId();
    }

    /** 取消本次创建会废止旧任期，保留完整审计；响应丢失后的重试不能影响下一位房主。 */
    @Test
    void cancellationPersistsAuditAndRejectsCancelledAcquisitionAcrossRestart() throws Exception {
        String world;
        WorldStore.Identity original;
        try (var store = WorldStore.open(directory)) {
            world = createWorld(store, "首次创建");
            original = identity(store.acquire(world, "房主", device, request).view());
            code("OWNER_MISMATCH", () -> store.cancelCreation(world,
                new WorldStore.Identity("访客", device, original.sessionId(), original.epoch())));
            code("STALE_EPOCH", () -> store.cancelCreation(world,
                new WorldStore.Identity("房主", device, original.sessionId(), original.epoch() + 1)));
            store.cancelCreation(world, original);
            assertEquals("FREE", store.list().getFirst().status());
            var metadata = WorldMetadata.parse(Files.readString(directory.resolve(world).resolve("world.json")));
            assertEquals(2, metadata.state().epoch());
            assertNull(metadata.state().owner());
            assertEquals(1, metadata.cancellations().size());
            assertTrue(metadata.cancellations().getFirst().matches(original));
        }
        try (var store = WorldStore.open(directory)) {
            store.cancelCreation(world, original);
            code("ACQUISITION_CANCELLED", () -> store.acquire(world, "房主", device, request));
            var next = store.acquire(world, "访客", id(), id()).view();
            assertEquals(3, next.epoch());
            store.cancelCreation(world, original);
            assertEquals("访客", store.list().getFirst().hostName());
            code("OWNER_MISMATCH", () -> store.ready(world, original, WorldStore.ReadyStage.SAVE_SYNCED, null, null));
        }
    }

    /** 最终开始生成与取消共享原子边界，竞争中只能有一种结果。 */
    @Test
    void cancellationAndGenerationCannotBothSucceed() throws Exception {
        try (var store = WorldStore.open(directory); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 30; attempt++) {
                String world = createWorld(store, "竞争" + attempt);
                var owner = identity(store.acquire(world, "房主", device, id()).view());
                CountDownLatch start = new CountDownLatch(1);
                var cancelling = executor.submit(() -> {
                    start.await();
                    try {
                        store.cancelCreation(world, owner);
                        return true;
                    } catch (WorldAccessException expected) {
                        return false;
                    }
                });
                var generating = executor.submit(() -> {
                    start.await();
                    try {
                        store.ready(world, owner, WorldStore.ReadyStage.SAVE_SYNCED, null, null);
                        return true;
                    } catch (WorldAccessException expected) {
                        return false;
                    }
                });
                start.countDown();
                assertNotEquals(cancelling.get(), generating.get());
            }
        }
    }

    /** 已有快照不能用取消首建解锁；hasSave 仅从权威版本推导，不依赖文件夹。 */
    @Test
    void existingSnapshotIsReportedAndCannotBeCancelled() throws Exception {
        String world;
        try (var store = WorldStore.open(directory)) {
            world = createWorld(store, "已有存档");
        }
        Path file = directory.resolve(world).resolve("world.json");
        var metadata = WorldMetadata.parse(Files.readString(file));
        Files.writeString(file, metadata.withState(new WorldMetadata.State(0, WorldPhase.FREE, null,
            null, 7L, "2026-11-01T00:00:00Z", null)).toJson());
        try (var store = WorldStore.open(directory)) {
            var view = store.acquire(world, "房主", device, request).view();
            JsonWriter writer = new JsonWriter();
            view.writeJson(writer);
            assertTrue(writer.finish().contains("\"hasSave\":true"));
            code("INVALID_PHASE", () -> store.cancelCreation(world, identity(view)));
            assertEquals(7L, view.baseVersion());
        }
    }

    /** 取消发布结果不确定时隔离世界；重启只认磁盘状态，不能使用旧内存继续抢锁。 */
    @Test
    void uncertainCancellationPublicationFailsClosed() throws Exception {
        for (boolean moved : List.of(false, true)) {
            Path root = directory.resolve(Boolean.toString(moved));
            AtomicBoolean cancelling = new AtomicBoolean();
            String world;
            WorldStore.Identity owner;
            try (var store = WorldStore.open(root, WorldCreationCancellationTest::move, UUID::randomUUID,
                (source, target) -> {
                    if (!cancelling.get() || moved) move(source, target);
                    if (cancelling.get()) throw new IOException("Injected uncertain cancellation");
                }, System::nanoTime, 10, 30)) {
                world = createWorld(store, "发布失败");
                owner = identity(store.acquire(world, "房主", device, request).view());
                cancelling.set(true);
                assertThrows(IOException.class, () -> store.cancelCreation(world, owner));
                code("WORLD_STATE_UNAVAILABLE", () -> store.acquire(world, "访客", id(), id()));
            }
            try (var store = WorldStore.open(root)) {
                if (moved) {
                    store.cancelCreation(world, owner);
                    code("ACQUISITION_CANCELLED", () -> store.acquire(world, "房主", device, request));
                    assertEquals(3, store.acquire(world, "访客", id(), id()).view().epoch());
                } else {
                    code("WORLD_OWNED", () -> store.acquire(world, "访客", id(), id()));
                    store.cancelCreation(world, owner);
                }
            }
        }
    }

    /** 验证多次取消创建后，重启仍会拒绝已取消的请求，新的获取请求使用递增任期。 */
    @Test
    void cancellationHistorySurvivesMultipleCreations() throws Exception {
        String world;
        try (var store = WorldStore.open(directory)) {
            world = createWorld(store, "多次取消");
            for (int i = 0; i < 25; i++) {
                var owner = identity(store.acquire(world, "房主", device, i == 0 ? request : id()).view());
                store.cancelCreation(world, owner);
            }
        }
        try (var store = WorldStore.open(directory)) {
            code("ACQUISITION_CANCELLED", () -> store.acquire(world, "房主", device, request));
            assertEquals(51, store.acquire(world, "房主", device, id()).view().epoch());
        }
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

}
