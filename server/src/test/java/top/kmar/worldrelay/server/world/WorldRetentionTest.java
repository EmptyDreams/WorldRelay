package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldRetentionTest {

    @TempDir
    Path root;
    private final TestClock clock = new TestClock();
    private final List<Saved> saved = new ArrayList<>();

    private static final class TestClock extends Clock {

        Instant now = Instant.parse("2026-11-08T12:00:00Z");

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

    private record Saved(String world, String upload, String hash, Long base, WorldStore.Identity owner, Path path) {

    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private WorldStore open() throws IOException {
        return WorldStore.open(root.resolve("worlds"), WorldRetentionTest::move, UUID::randomUUID,
            WorldRetentionTest::move, System::nanoTime, 10, 30, clock);
    }

    private WorldRetention retention(WorldStore store, RetentionConfig config) {
        return new WorldRetention(store, root.resolve("transfers"), config, clock, ignored -> Long.MAX_VALUE);
    }

    private RetentionConfig policy(int recent, int days) {
        return new RetentionConfig(recent, days, 24, 1024, 60, ZoneOffset.UTC);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String create(WorldStore store, String name) throws IOException {
        var world = store.create(name);
        assertNotNull(world);
        return world.worldId();
    }

    private WorldStore.Identity acquire(WorldStore store, String world) throws Exception {
        String device = id();
        var acquisition = store.acquire(world, "Dev", device, id());
        return new WorldStore.Identity("Dev", device, acquisition.view().sessionId(), acquisition.view().epoch());
    }

    private Saved save(WorldStore store, String world, String at) throws Exception {
        clock.now = Instant.parse(at);
        var owner = acquire(store, world);
        Long base = store.heartbeat(world, owner, null).baseVersion();
        store.stop(world, owner, WorldStore.StopStage.BEGIN, base);
        store.prepareTransfer(world, owner, base, true);
        Path payload = store.candidateDirectory(world, owner).resolve("payload");
        Files.createDirectories(payload.resolve("world"));
        Files.writeString(payload.resolve("world/level.dat"), at);
        byte[] manifest = SnapshotFiles.scan(payload.resolve("world")).encode();
        Files.write(payload.resolve("manifest.bin"), manifest);
        String upload = id(), hash = SnapshotManifest.hash(manifest);
        store.publishSnapshot(world, owner, base, upload, hash, payload);
        var result = new Saved(world, upload, hash, base, owner,
            root.resolve("worlds").resolve(world).resolve("snapshots").resolve(upload));
        saved.add(result);
        return result;
    }

    /** 最近五版与七个自然日每天最后一版取并集；过期后清理仍保留提交回执及最新下载基础。 */
    @Test
    void retainsRecentAndDailyUnionAndKeepsCommitRetriesAfterRestart() throws Exception {
        String world;
        Saved first;
        try (var store = open(); var retention = retention(store, policy(5, 7))) {
            world = create(store, "备份");
            for (int day = 1; day <= 8; day++) {
                save(store, world, "2026-11-%02dT08:00:00Z".formatted(day));
                save(store, world, "2026-11-%02dT09:00:00Z".formatted(day));
            }
            for (int hour = 10; hour < 15; hour++) {
                save(store, world, "2026-11-08T%02d:00:00Z".formatted(hour));
            }
            first = saved.getFirst();
            retention.sweep();
            Set<Integer> expected = Set.of(4, 6, 8, 10, 12, 14, 17, 18, 19, 20, 21);
            for (int i = 0; i < saved.size(); i++) {
                assertEquals(expected.contains(i + 1), Files.exists(saved.get(i).path()), "version " + (i + 1));
            }
            clock.now = Instant.parse("2026-11-10T12:00:00Z");
            retention.sweep();
            assertFalse(Files.exists(saved.get(3).path()));
            assertFalse(Files.exists(saved.get(5).path()));
            assertTrue(Files.exists(saved.get(7).path()));
        }
        try (var store = open()) {
            assertEquals(1L, store.committedResult(world, first.owner(), first.upload(), first.hash(), null));
            var owner = acquire(store, world);
            var source = store.prepareTransfer(world, owner, 21L, false);
            assertNotNull(source);
            assertEquals(saved.getLast().path(), source.directory());
        }
    }

    /** 每日边界按配置时区计算，同一天多次提交只保留最后一份。 */
    @Test
    void selectsDailyBackupAcrossLocalMidnight() throws Exception {
        var config = new RetentionConfig(1, 2, 24, 0, 60, ZoneId.of("Asia/Shanghai"));
        try (var store = open(); var retention = retention(store, config)) {
            String world = create(store, "时区");
            var earlier = save(store, world, "2026-11-07T23:00:00Z");
            var lastOfDay = save(store, world, "2026-11-08T15:00:00Z");
            var current = save(store, world, "2026-11-08T17:00:00Z");
            clock.now = Instant.parse("2026-11-09T00:30:00Z");
            retention.sweep();
            assertFalse(Files.exists(earlier.path()));
            assertTrue(Files.exists(lastOfDay.path()));
            assertTrue(Files.exists(current.path()));
        }
    }

    private Path expiredDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        Files.writeString(path.resolve("data"), "temporary");
        Files.setLastModifiedTime(path, FileTime.from(clock.now.minus(Duration.ofHours(25))));
        return path;
    }

    /** 临时目录按 24 小时清理，活动复制及离线有效任期的上传副本受到保护。 */
    @Test
    @SuppressWarnings({"try", "unused"})
    // 资源持有期间模拟尚未完成的复制。
    void cleansExpiredTemporariesButProtectsActiveOperationsAndRecovery() throws Exception {
        try (var store = open(); var retention = retention(store, policy(5, 7))) {
            String world = create(store, "恢复");
            save(store, world, "2026-11-08T09:00:00Z");
            var owner = acquire(store, world);
            Path worldRoot = root.resolve("worlds").resolve(world);
            Path candidate = expiredDirectory(worldRoot.resolve(".candidate-old"));
            Path fresh = Files.createDirectory(worldRoot.resolve(".candidate-new"));
            Files.setLastModifiedTime(fresh, FileTime.from(clock.now.minus(Duration.ofHours(23))));
            Path creating = expiredDirectory(root.resolve("worlds/.creating-old"));
            Path epochRoot = root.resolve("transfers/.sessions/" + "a".repeat(64)).resolve(world);
            Path interruptedCleanup = expiredDirectory(epochRoot.resolve(".retention-trash-interrupted"));
            Path oldSession = expiredDirectory(epochRoot.resolve("1/upload-" + id()));
            Path currentSession = expiredDirectory(epochRoot.resolve(owner.epoch() + "/upload-" + id()));
            Path seed = expiredDirectory(currentSession.resolve(".seed-failed"));
            Files.setLastModifiedTime(currentSession, FileTime.from(clock.now.minus(Duration.ofHours(25))));
            try (var operation = retention.protect(world)) {
                retention.sweep();
                assertTrue(Files.exists(candidate));
                assertTrue(Files.exists(oldSession));
                assertFalse(Files.exists(creating));
            }
            retention.sweep();
            assertFalse(Files.exists(candidate));
            assertFalse(Files.exists(seed));
            assertFalse(Files.exists(oldSession));
            assertFalse(Files.exists(interruptedCleanup));
            assertTrue(Files.exists(fresh));
            assertTrue(Files.exists(currentSession.resolve("data")));
            assertEquals(owner.epoch(), store.heartbeat(world, owner, null).epoch());
        }
        try (var store = open(); var retention = retention(store, policy(5, 7))) {
            assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
            retention.sweep();
            try (var files = Files.walk(root.resolve("transfers"))) {
                assertEquals(1, files.filter(path -> path.getFileName().toString().equals("data")).count());
            }
        }
    }

    /** 空间紧张时跨世界先删除最旧备份，可少于五版；权威快照和有效所有权始终保留。 */
    @Test
    void evictsGloballyOldestBackupsAndFailsSafelyWhenOnlyAuthorityRemains() throws Exception {
        try (var store = open()) {
            String a = create(store, "A"), b = create(store, "B");
            var aOld = save(store, a, "2026-11-02T00:00:00Z");
            var aCurrent = save(store, a, "2026-11-03T00:00:00Z");
            var bOld = save(store, b, "2026-11-01T00:00:00Z");
            var bCurrent = save(store, b, "2026-11-04T00:00:00Z");
            var owner = acquire(store, a);
            WorldRetention.SpaceProbe probe = ignored -> {
                if (Files.exists(bOld.path())) assertTrue(Files.exists(aOld.path()), "oldest backup must be evicted first");
                return Files.exists(aOld.path()) || Files.exists(bOld.path()) ? 0 : 1536;
            };
            try (var retention = new WorldRetention(store, root.resolve("transfers"), policy(5, 7), clock, probe)) {
                retention.ensureSpace(root.resolve("worlds"), 512);
                assertFalse(Files.exists(aOld.path()));
                assertFalse(Files.exists(bOld.path()));
                assertTrue(Files.exists(aCurrent.path()));
                assertTrue(Files.exists(bCurrent.path()));
                var error = assertThrows(WorldAccessException.class, () -> retention.ensureSpace(root.resolve("worlds"), 513));
                assertEquals("INSUFFICIENT_STORAGE", error.code());
                assertEquals(owner.epoch(), store.heartbeat(a, owner, null).epoch());
                var source = store.prepareTransfer(a, owner, 2L, false);
                assertNotNull(source);
                assertEquals(aCurrent.path(), source.directory());
            }
        }
    }

    /** 元数据发布结果不确定时，整个世界的快照和候选均留给重启恢复。 */
    @Test
    void protectsWorldWithUncertainMetadataPublication() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        try (var store = WorldStore.open(root.resolve("worlds"), WorldRetentionTest::move, UUID::randomUUID,
            (source, target) -> {
                if (fail.get()) throw new IOException("uncertain publication");
                move(source, target);
            }, System::nanoTime, 10, 30, clock);
             var retention = retention(store, policy(1, 0))) {
            String world = create(store, "不确定提交");
            var old = save(store, world, "2026-11-01T00:00:00Z");
            var current = save(store, world, "2026-11-02T00:00:00Z");
            var owner = acquire(store, world);
            Path candidate = expiredDirectory(store.candidateDirectory(world, owner));
            fail.set(true);
            assertThrows(IOException.class, () -> store.stop(world, owner, WorldStore.StopStage.BEGIN, 2L));
            clock.now = Instant.parse("2026-11-08T00:00:00Z");
            retention.sweep();
            assertTrue(Files.exists(old.path()));
            assertTrue(Files.exists(current.path()));
            assertTrue(Files.exists(candidate));
        }
    }

    /** 清理包含符号链接的临时目录时只删除链接，不触碰目录外的数据。 */
    @Test
    void neverFollowsLinksOutsideManagedDirectories() throws Exception {
        try (var store = open(); var retention = retention(store, policy(5, 7))) {
            String world = create(store, "链接");
            Path outside = Files.createDirectory(root.resolve("outside"));
            Files.writeString(outside.resolve("precious"), "keep");
            Path candidate = expiredDirectory(root.resolve("worlds").resolve(world).resolve(".candidate-linked"));
            try {
                Files.createSymbolicLink(candidate.resolve("link"), outside);
            } catch (IOException | UnsupportedOperationException unsupported) {
                assumeTrue(false, "Symbolic links unavailable: " + unsupported.getMessage());
            }
            Files.setLastModifiedTime(candidate, FileTime.from(clock.now.minus(Duration.ofHours(25))));
            retention.sweep();
            assertFalse(Files.exists(candidate));
            assertEquals("keep", Files.readString(outside.resolve("precious")));
        }
    }

    /** 已部署的 v4 元数据用快照目录时间补足每日分组，升级后继续写入带时间的回执。 */
    @Test
    void retainsLegacySnapshotsAndMigratesMetadataWithoutLosingReceipts() throws Exception {
        String world;
        Saved old;
        try (var store = open()) {
            world = create(store, "旧存档");
            old = save(store, world, "2026-11-06T10:00:00Z");
            save(store, world, "2026-11-08T10:00:00Z");
        }
        Path metadataFile = root.resolve("worlds").resolve(world).resolve("world.json");
        var metadata = WorldMetadata.parse(Files.readString(metadataFile));
        var legacy = metadata.commits().stream().map(commit -> new WorldMetadata.Commit(commit.uploadId(), commit.owner(),
            commit.epoch(), commit.baseVersion(), commit.version(), commit.manifestHash())).toList();
        Files.writeString(metadataFile, new WorldMetadata(metadata.worldId(), metadata.name(), metadata.createdAt(), metadata.state(),
            metadata.cancellations(), legacy).toJson());
        Files.setLastModifiedTime(old.path(), FileTime.from(Instant.parse("2026-11-06T10:00:00Z")));
        try (var store = open(); var retention = retention(store, policy(1, 7))) {
            retention.sweep();
            assertTrue(Files.exists(old.path()));
            save(store, world, "2026-11-08T11:00:00Z");
            var upgraded = WorldMetadata.parse(Files.readString(metadataFile));
            assertNull(upgraded.commits().getFirst().committedAt());
            assertEquals(clock.now.toString(), upgraded.commits().getLast().committedAt());
            assertEquals(1L, store.committedResult(world, old.owner(), old.upload(), old.hash(), old.base()));
        }
    }

}
