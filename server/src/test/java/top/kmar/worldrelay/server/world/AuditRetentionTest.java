package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuditRetentionTest {

    @TempDir
    Path root;
    private static final String USER = "房".repeat(64);
    private static final String REASON = "审".repeat(256);
    private static final String AT = "2026-11-08T12:00:00Z";

    private record Seed(Path store, String world, WorldMetadata original) {

        Path metadata() {
            return store.resolve(world).resolve("world.json");
        }

        WorldMetadata read() throws IOException {
            assertTrue(Files.size(metadata()) <= WorldStore.MAX_METADATA_BYTES);
            return WorldMetadata.parse(Files.readString(metadata()));
        }

    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static WorldStore.Identity acquire(WorldStore store, String world) throws Exception {
        String device = id();
        var view = store.acquire(world, USER, device, id()).view();
        return new WorldStore.Identity(USER, device, view.sessionId(), view.epoch());
    }

    private static WorldStore.Identity identity(WorldMetadata.Cancellation audit) {
        return new WorldStore.Identity(audit.owner().username(), audit.owner().deviceId(), audit.owner().sessionId(), audit.epoch());
    }

    private static WorldAbandonment.Request request(String world, WorldMetadata.Cancellation audit) {
        return new WorldAbandonment.Request(world, audit.abandonId(), audit.epoch(), audit.committedVersion(), audit.phase(),
            audit.owner().username(), audit.lastCommittedAt(), audit.reason());
    }

    private static WorldAbandonment.Request preview(WorldStore store, String world) throws Exception {
        var view = store.previewAbandon(world);
        return new WorldAbandonment.Request(world, view.abandonId(), view.expectedEpoch(), view.expectedVersion(), view.expectedPhase(),
            view.ownerUsername(), view.lastCommittedAt(), REASON);
    }

    private static WorldMetadata metadata(WorldMetadata template, List<WorldMetadata.Cancellation> history) {
        long epoch = history.getLast().epoch() + 1;
        List<WorldMetadata.Commit> commits = template.commits();
        if (!commits.isEmpty()) {
            var last = commits.getLast();
            commits = List.of(commits.getFirst(), new WorldMetadata.Commit(last.uploadId(), last.owner(), epoch, last.baseVersion(),
                last.version(), last.manifestHash(), last.committedAt()));
        }
        var state = new WorldMetadata.State(epoch, WorldPhase.FREE, null, null, template.state().committedVersion(),
            template.state().lastCommittedAt(), null);
        return new WorldMetadata(template.worldId(), template.name(), template.createdAt(), state, history, commits);
    }

    private static Seed seed(Path directory, boolean admin, WorldMetadata template) throws Exception {
        var history = new ArrayList<WorldMetadata.Cancellation>();
        Long base = template.commits().isEmpty() ? null : 1L;
        String committedAt = base == null ? null : template.commits().getFirst().committedAt();
        for (int i = 0; i < 3000; i++) {
            var owner = new WorldMetadata.Owner(USER, id(), id(), id());
            long epoch = 2L * i + (base == null ? 1 : 2);
            history.add(admin ? new WorldMetadata.Cancellation(owner, epoch, AT, id(), base, committedAt, WorldPhase.STOPPING, REASON)
                : new WorldMetadata.Cancellation(owner, epoch, AT));
        }
        int low = 1, high = history.size();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metadata(template, history.subList(0, middle)).toJson().getBytes(StandardCharsets.UTF_8).length <= WorldStore.MAX_METADATA_BYTES)
                low = middle;
            else high = middle - 1;
        }
        assertTrue(low < history.size());
        var original = metadata(template, history.subList(0, low));
        var seed = new Seed(directory, template.worldId(), original);
        Files.writeString(seed.metadata(), original.toJson());
        return seed;
    }

    private static Seed seed(Path directory, boolean admin) throws Exception {
        String world;
        try (var store = WorldStore.open(directory)) {
            var created = store.create("审计容量测试");
            assertNotNull(created);
            world = created.worldId();
        }
        return seed(directory, admin, WorldMetadata.parse(Files.readString(directory.resolve(world).resolve("world.json"))));
    }

    /** 真实 UTF-8 容量边界下取消和放弃均能淘汰旧审计，重启保留近期幂等结果并拒绝旧任期修改新房主。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsAuditHistoryAndRetainsRecentRetriesAcrossRestart(boolean admin) throws Exception {
        var seed = seed(root, admin);
        var oldest = seed.original().cancellations().getFirst();
        WorldStore.Identity next;
        WorldMetadata.Cancellation recent;
        try (var store = WorldStore.open(root)) {
            var owner = acquire(store, seed.world());
            if (admin) store.abandon(preview(store, seed.world()));
            else store.cancelCreation(seed.world(), owner);
            var compacted = seed.read();
            assertEquals("FREE", store.list().getFirst().status());
            assertTrue(compacted.cancellations().getFirst().epoch() > oldest.epoch());
            recent = compacted.cancellations().getLast();
            assertTrue(recent.matches(owner));
            assertTrue(compacted.commits().isEmpty());
            next = acquire(store, seed.world());
        }
        try (var store = WorldStore.open(root)) {
            if (admin) {
                assertEquals(recent.epoch() + 1, store.abandon(request(seed.world(), recent)).epoch());
                assertEquals("WORLD_CHANGED", assertThrows(WorldAccessException.class,
                    () -> store.abandon(request(seed.world(), oldest))).code());
            } else {
                store.cancelCreation(seed.world(), identity(recent));
                assertThrows(WorldAccessException.class, () -> store.cancelCreation(seed.world(), identity(oldest)));
            }
            assertThrows(WorldAccessException.class, () -> store.publishSnapshot(seed.world(), identity(oldest), null,
                id(), "a".repeat(64), root.resolve("unused")));
            assertEquals(next.epoch(), store.heartbeat(seed.world(), next, null).epoch());
            assertEquals(next, identityOf(seed.read().state()));
        }
    }

    private static WorldStore.Identity identityOf(WorldMetadata.State state) {
        var owner = state.owner();
        assertNotNull(owner);
        return new WorldStore.Identity(owner.username(), owner.deviceId(), owner.sessionId(), state.epoch());
    }

    /** 混合历史按任期淘汰；保留当前权威回执，旧回执对应的实际备份仍可列出及恢复。 */
    @Test
    void compactsMixedHistoryWithoutLosingSnapshotReferences() throws Exception {
        var data = BackupTestWorld.create(root);
        var template = WorldMetadata.parse(Files.readString(data.metadata()));
        var seed = seed(root, true, template);
        try (var store = WorldStore.open(root)) {
            for (int i = 0; i < 2; i++) {
                acquire(store, seed.world());
                store.abandon(preview(store, seed.world()));
            }
            var compacted = seed.read();
            assertEquals(List.of(template.commits().getLast().uploadId()), compacted.commits().stream().map(WorldMetadata.Commit::uploadId).toList());
            assertTrue(compacted.cancellations().getFirst().epoch() > seed.original().cancellations().getFirst().epoch());
            assertEquals(2L, compacted.state().committedVersion());
            assertEquals("first-world", Files.readString(data.snapshot(data.first()).resolve("world/level.dat")));
            assertEquals("second-world", Files.readString(data.snapshot(data.second()).resolve("world/level.dat")));
            assertEquals(2, store.snapshotReferences(compacted).size());
            try (var retention = new WorldRetention(store, root.resolve("transfers"), RetentionConfig.defaults())) {
                assertEquals(1L, new WorldBackups(store, retention).preview(seed.world(), 1).backupVersion());
            }
        }
    }

    /** 审计淘汰与放弃原子发布；替换前后出现异常都保守隔离，重启可安全重试且不重复撤销任期。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoversCompactionAfterUncertainPublication(boolean moved) throws Exception {
        var seed = seed(root, true);
        var failing = new AtomicReference<String>();
        WorldAbandonment.Request request;
        try (var store = WorldStore.open(root, AuditRetentionTest::move, UUID::randomUUID, (source, target) -> {
            if (failing.get() != null && Files.readString(source).contains(failing.get())) {
                if (moved) move(source, target);
                throw new IOException("Injected uncertain publication");
            }
            move(source, target);
        }, System::nanoTime, 10, 30)) {
            acquire(store, seed.world());
            request = preview(store, seed.world());
            failing.set(request.abandonId());
            assertThrows(IOException.class, () -> store.abandon(request));
            assertEquals("WORLD_STATE_UNAVAILABLE", assertThrows(WorldAccessException.class,
                () -> store.previewAbandon(seed.world())).code());
        }
        try (var store = WorldStore.open(root)) {
            var result = store.abandon(request);
            assertEquals(request.expectedEpoch() + 1, result.epoch());
            var compacted = seed.read();
            assertTrue(compacted.cancellations().getFirst().epoch() > seed.original().cancellations().getFirst().epoch());
            assertEquals(request.abandonId(), compacted.cancellations().getLast().abandonId());
            var next = acquire(store, seed.world());
            assertEquals(result, store.abandon(request));
            assertEquals(next.epoch(), store.heartbeat(seed.world(), next, null).epoch());
        }
    }

}
