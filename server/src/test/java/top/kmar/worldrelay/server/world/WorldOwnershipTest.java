package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.protocol.HostEndpoint;
import top.kmar.worldrelay.server.protocol.OwnershipView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static top.kmar.worldrelay.server.world.WorldStore.ReadyStage.PORT_OPENED;
import static top.kmar.worldrelay.server.world.WorldStore.ReadyStage.SAVE_SYNCED;
import static top.kmar.worldrelay.server.world.WorldStore.StopStage.BEGIN;
import static top.kmar.worldrelay.server.world.WorldStore.StopStage.UPLOAD_COMPLETE;

class WorldOwnershipTest {

    @TempDir
    Path directory;
    private final AtomicLong time = new AtomicLong();
    private final String device = id();
    private final String request = id();
    private final HostEndpoint endpoint = new HostEndpoint("100.64.0.10", 25565);

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private WorldStore open() throws IOException {
        return WorldStore.open(directory, WorldOwnershipTest::move, UUID::randomUUID,
            WorldOwnershipTest::move, time::get, 10, 30);
    }

    private WorldStore.Identity identity(OwnershipView view) {
        return new WorldStore.Identity("房主", device, view.sessionId(), view.epoch());
    }

    private static void error(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(WorldAccessException.class, operation).code());
    }

    /**
     * 验证获取、两阶段 ready 和两阶段 stop 的完整状态流转，重复及迟到请求不能回退阶段。
     * 分别在停止中和报告上传完成后重启，确认会话、任期及退出进度保留，权威版本未发布且访客不能接管。
     */
    @Test
    void lifecycleRetainsOwnershipAndStopProgressAcrossRestart() throws Exception {
        String world;
        WorldStore.Identity owner;
        try (WorldStore store = open()) {
            var created = store.create("接力世界");
            assertNotNull(created, "测试前置条件：接力世界应创建成功");
            world = created.worldId();
            var acquired = store.acquire(world, "房主", device, request);
            owner = identity(acquired.view());
            assertTrue(acquired.acquired());
            assertEquals(1, owner.epoch());
            assertEquals("PREPARING", acquired.view().status());
            assertNull(acquired.view().baseVersion());
            var retry = store.acquire(world, "房主", device, request);
            assertFalse(retry.acquired());
            assertEquals(acquired.view(), retry.view());
            error("WORLD_OWNED", () -> store.acquire(world, "房主", id(), request));
            error("WORLD_OWNED", () -> store.acquire(world, "房主", device, id()));
            error("INVALID_PHASE", () -> store.ready(world, owner, PORT_OPENED, null, endpoint));
            error("INVALID_PHASE", () -> store.stop(world, owner, UPLOAD_COMPLETE, null));
            error("BASE_VERSION_MISMATCH", () -> store.ready(world, owner, SAVE_SYNCED, 1L, null));
            assertEquals(WorldPhase.STARTING, store.ready(world, owner, SAVE_SYNCED, null, null).phase());
            assertNull(store.list().getFirst().endpoint());
            assertEquals(WorldPhase.STARTING, store.ready(world, owner, SAVE_SYNCED, null, null).phase());
            var running = store.ready(world, owner, PORT_OPENED, null, endpoint);
            assertEquals("RUNNING", running.status());
            assertEquals(endpoint, store.list().getFirst().endpoint());
            assertEquals(running, store.ready(world, owner, PORT_OPENED, null, endpoint));
            assertEquals(WorldPhase.RUNNING, store.ready(world, owner, SAVE_SYNCED, null, null).phase());
            error("ENDPOINT_CONFLICT", () -> store.ready(world, owner, PORT_OPENED, null,
                new HostEndpoint(endpoint.address(), endpoint.port() + 1)));
            store.heartbeat(world, owner, 2);
            assertEquals(2, store.list().getFirst().playerCount());
            assertEquals(WorldPhase.STOPPING, store.stop(world, owner, BEGIN, null).phase());
            assertNull(store.list().getFirst().endpoint());
            assertEquals(WorldPhase.STOPPING, store.stop(world, owner, BEGIN, null).phase());
            error("INVALID_PHASE", () -> store.ready(world, owner, PORT_OPENED, null, endpoint));
        }
        try (WorldStore store = open()) {
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
            assertEquals(WorldPhase.STOPPING, store.heartbeat(world, owner, null).phase());
            error("BASE_VERSION_MISMATCH", () -> store.stop(world, owner, UPLOAD_COMPLETE, 1L));
            var complete = store.stop(world, owner, UPLOAD_COMPLETE, null);
            assertEquals(WorldPhase.COMMITTING, complete.phase());
            assertNull(complete.committedVersion());
            assertNull(complete.endpoint());
            assertEquals(complete, store.stop(world, owner, UPLOAD_COMPLETE, null));
            assertEquals(WorldPhase.COMMITTING, store.stop(world, owner, BEGIN, null).phase());
            error("INVALID_PHASE", () -> store.ready(world, owner, SAVE_SYNCED, null, null));
            error("WORLD_OWNED", () -> store.acquire(world, "访客", id(), id()));
            assertNull(store.list().getFirst().lastCommittedAt());
        }
        try (WorldStore store = open()) {
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
            var retry = store.acquire(world, "房主", device, request);
            assertFalse(retry.acquired());
            assertEquals(owner.epoch(), retry.view().epoch());
            assertEquals(owner.sessionId(), retry.view().sessionId());
            assertEquals(WorldPhase.COMMITTING, retry.view().phase());
            assertEquals("RECOVERY_REQUIRED", retry.view().status());
            error("WORLD_OWNED", () -> store.acquire(world, "访客", id(), id()));
        }
    }

    /**
     * 用假时钟覆盖 30 秒超时边界和单调计时值溢出，验证超时只撤下端点、清除过期玩家数量，不释放所有权。
     * 超时或重启后，心跳和迟到的 SAVE_SYNCED 均不能恢复可加入状态，必须重新报告 PORT_OPENED。
     */
    @Test
    void timeoutAndRestartRequireExplicitPortReconfirmation() throws Exception {
        time.set(Long.MAX_VALUE - 20_000_000_000L);
        String world;
        WorldStore.Identity owner;
        try (WorldStore store = open()) {
            var created = store.create("超时世界");
            assertNotNull(created, "测试前置条件：超时世界应创建成功");
            world = created.worldId();
            owner = identity(store.acquire(world, "房主", device, request).view());
            store.ready(world, owner, SAVE_SYNCED, null, null);
            store.ready(world, owner, PORT_OPENED, null, endpoint);
            time.addAndGet(29_999_999_999L);
            assertEquals("RUNNING", store.list().getFirst().status());
            time.incrementAndGet();
            // 在没有查询触发超时的情况下，心跳也必须先撤销旧的就绪标记。
            assertEquals("RECOVERY_REQUIRED", store.heartbeat(world, owner, 2).status());
            assertNull(store.list().getFirst().endpoint());
            error("WORLD_OWNED", () -> store.acquire(world, "访客", id(), id()));
            assertEquals("RECOVERY_REQUIRED", store.ready(world, owner, SAVE_SYNCED, null, null).status());
            assertEquals("RUNNING", store.ready(world, owner, PORT_OPENED, null, endpoint).status());
            time.addAndGet(30_000_000_000L);
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
            assertNull(store.list().getFirst().playerCount());
            store.ready(world, owner, PORT_OPENED, null, endpoint);
        }
        try (WorldStore store = open()) {
            assertNull(store.list().getFirst().endpoint());
            assertEquals("RECOVERY_REQUIRED", store.heartbeat(world, owner, null).status());
            assertEquals("RUNNING", store.ready(world, owner, PORT_OPENED, null, endpoint).status());
        }
    }

    /**
     * 验证错误用户、设备、会话、任期和不存在的世界分别被拒绝。
     * 失败请求不能改写元数据或刷新探活时间，已超时的世界仍显示等待恢复。
     */
    @Test
    void rejectsWrongOwnerDeviceSessionAndEpochWithoutRefreshingHeartbeat() throws Exception {
        try (WorldStore store = open()) {
            var created = store.create("身份世界");
            assertNotNull(created, "测试前置条件：身份世界应创建成功");
            String world = created.worldId();
            var view = store.acquire(world, "房主", device, request).view();
            var owner = identity(view);
            Path file = directory.resolve(world).resolve("world.json");
            String original = Files.readString(file);
            time.addAndGet(30_000_000_000L);
            for (var wrong : List.of(new WorldStore.Identity("访客", device, owner.sessionId(), owner.epoch()),
                new WorldStore.Identity("房主", id(), owner.sessionId(), owner.epoch()))) {
                var failure = assertThrows(WorldAccessException.class, () -> store.heartbeat(world, wrong, 99));
                assertEquals(403, failure.status());
                assertEquals("OWNER_MISMATCH", failure.code());
            }
            error("SESSION_MISMATCH", () -> store.ready(world,
                new WorldStore.Identity("房主", device, id(), owner.epoch()), SAVE_SYNCED, null, null));
            error("STALE_EPOCH", () -> store.stop(world,
                new WorldStore.Identity("房主", device, owner.sessionId(), owner.epoch() + 1), BEGIN, null));
            error("WORLD_NOT_FOUND", () -> store.heartbeat(id(), owner, 0));
            assertEquals(original, Files.readString(file));
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
        }
    }

    /**
     * 分别模拟元数据原子替换前失败、替换成功后抛异常，验证结果不确定时隔离世界并拒绝继续操作。
     * 重启后依据磁盘恢复：未替换时允许首次获取，已替换时只能返回原会话，不能再授予第二份所有权。
     */
    @Test
    void publicationFailureBeforeOrAfterMoveFailsClosedUntilReopen() throws Exception {
        for (boolean moved : List.of(false, true)) {
            Path root = directory.resolve(Boolean.toString(moved));
            String world;
            try (WorldStore store = WorldStore.open(root, WorldOwnershipTest::move, UUID::randomUUID,
                (source, target) -> {
                    if (moved) move(source, target);
                    throw new IOException("Injected uncertain publication");
                }, time::get, 10, 30)) {
                var created = store.create("失败世界");
                assertNotNull(created, "测试前置条件：失败世界应创建成功");
                world = created.worldId();
                assertThrows(IOException.class, () -> store.acquire(world, "房主", device, request));
                assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
                error("WORLD_STATE_UNAVAILABLE", () -> store.acquire(world, "访客", id(), id()));
                try (var files = Files.list(root.resolve(world))) {
                    assertEquals(1, files.count());
                }
            }
            try (WorldStore store = WorldStore.open(root)) {
                var result = store.acquire(world, "房主", device, request);
                assertEquals(!moved, result.acquired());
                assertEquals(1, result.view().epoch());
                if (moved) assertEquals("RECOVERY_REQUIRED", result.view().status());
            }
        }
    }

    /**
     * 模拟开始停止时元数据发布失败，验证服务端撤下端点并拒绝继续处理该世界的心跳。
     * 重启后仍保留磁盘上的原持有者和 RUNNING 阶段，不能因为停止失败而允许访客接管。
     */
    @Test
    void failedStopWithdrawsEndpointWithoutReleasingPersistedOwner() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        String world;
        try (WorldStore store = WorldStore.open(directory, WorldOwnershipTest::move, UUID::randomUUID,
            (source, target) -> {
                if (fail.get()) throw new IOException("Injected failure");
                move(source, target);
            },
            time::get, 10, 30)) {
            var created = store.create("停止失败");
            assertNotNull(created, "测试前置条件：停止失败世界应创建成功");
            world = created.worldId();
            var owner = identity(store.acquire(world, "房主", device, request).view());
            store.ready(world, owner, SAVE_SYNCED, null, null);
            store.ready(world, owner, PORT_OPENED, null, endpoint);
            fail.set(true);
            assertThrows(IOException.class, () -> store.stop(world, owner, BEGIN, null));
            assertNull(store.list().getFirst().endpoint());
            error("WORLD_STATE_UNAVAILABLE", () -> store.heartbeat(world, owner, 0));
        }
        try (WorldStore store = open()) {
            assertEquals(WorldPhase.RUNNING, store.acquire(world, "房主", device, request).view().phase());
            error("WORLD_OWNED", () -> store.acquire(world, "访客", id(), id()));
        }
    }

    /**
     * 验证读取 v1 世界记录时不改写文件，首次获取所有权才原子升级为 v2。
     * 升级后重启必须恢复已持有状态并等待原房主恢复，不能重新变为空闲世界。
     */
    @Test
    void readsLegacyWithoutWritingThenUpgradesOnAcquire() throws Exception {
        String world = id();
        Path file = Files.createDirectories(directory.resolve(world)).resolve("world.json");
        String legacy = "{\"formatVersion\":1,\"worldId\":\"" + world
            + "\",\"name\":\"旧世界\",\"createdAt\":\"2026-11-01T00:00:00Z\"}";
        Files.writeString(file, legacy);
        try (WorldStore store = open()) {
            assertEquals("FREE", store.list().getFirst().status());
            assertEquals(legacy, Files.readString(file));
            store.acquire(world, "房主", device, request);
            assertTrue(Files.readString(file).contains("\"formatVersion\":2"));
        }
        try (WorldStore store = open()) {
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
        }
    }

    /**
     * 逐项移除 v2 必需字段，或构造未知阶段、缺少持有者及版本时间不一致的状态，验证启动失败且原文件不被修复。
     * 恢复有效文件后能重新打开存储，确认每次失败均释放了进程文件锁。
     */
    @Test
    void missingOrInconsistentV2StateRefusesStartupWithoutRepairingFiles() throws Exception {
        String world;
        try (WorldStore store = open()) {
            var created = store.create("损坏世界");
            assertNotNull(created, "测试前置条件：损坏世界应创建成功");
            world = created.worldId();
        }
        Path file = directory.resolve(world).resolve("world.json");
        String valid = Files.readString(file);
        for (String invalid : List.of(valid.replace("\"epoch\":0,", ""),
            valid.replace("\"owner\":null,", ""), valid.replace("\"phase\":\"FREE\"", "\"phase\":\"RUNNING\""),
            valid.replace("\"phase\":\"FREE\"", "\"phase\":\"UNKNOWN\""),
            valid.replace("\"committedVersion\":null", "\"committedVersion\":1"),
            valid.substring(0, valid.indexOf(",\"state\"")) + "}")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, this::open);
            assertEquals(invalid, Files.readString(file));
        }
        Files.writeString(file, valid);
        try (WorldStore store = open()) {
            assertEquals("FREE", store.list().getFirst().status());
        }
    }

    /**
     * 从任期 41、权威版本 7 的空闲记录获取所有权，验证新任期为 42，基础版本固定为 7，并拒绝版本 6 的就绪声明。
     * 报告上传完成不能更新原提交时间，重启后的获取重试仍返回任期 42。
     */
    @Test
    void acquisitionFencesAgainstPreviousEpochAndBindsCommittedVersion() throws Exception {
        String world = id();
        Path file = Files.createDirectories(directory.resolve(world)).resolve("world.json");
        var state = new WorldMetadata.State(41, WorldPhase.FREE, null, null, 7L, "2026-11-01T00:00:00Z", null);
        Files.writeString(file, new WorldMetadata(world, "已有版本", "2026-11-01T00:00:00Z", state).toJson());
        try (WorldStore store = open()) {
            var acquired = store.acquire(world, "房主", device, request).view();
            assertEquals(42, acquired.epoch());
            assertEquals(7L, acquired.baseVersion());
            var owner = identity(acquired);
            error("BASE_VERSION_MISMATCH", () -> store.ready(world, owner, SAVE_SYNCED, 6L, null));
            store.ready(world, owner, SAVE_SYNCED, 7L, null);
            store.stop(world, owner, BEGIN, null);
            store.stop(world, owner, UPLOAD_COMPLETE, 7L);
            assertEquals("2026-11-01T00:00:00Z", store.list().getFirst().lastCommittedAt());
        }
        try (WorldStore store = open()) {
            assertEquals(42, store.acquire(world, "房主", device, request).view().epoch());
        }
    }

}
