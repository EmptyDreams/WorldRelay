package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CommitReceiptRetentionTest {

    @TempDir
    Path root;
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String USER = "房".repeat(64);

    private record Seed(Path store, String world, WorldMetadata original, String hash, byte[] manifest) {

        Path directory() {
            return store.resolve(world);
        }

        Path metadata() {
            return directory().resolve("world.json");
        }

        Path snapshot(String upload) {
            return directory().resolve("snapshots").resolve(upload);
        }

        Path index(String upload) {
            return directory().resolve("snapshot-index").resolve(upload + ".json");
        }

        long version() {
            return original.state().committedVersion();
        }

    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private WorldStore open(Seed seed) throws IOException {
        return WorldStore.open(seed.store(), CommitReceiptRetentionTest::move, UUID::randomUUID,
            CommitReceiptRetentionTest::move, System::nanoTime, 10, 30, CLOCK);
    }

    private static WorldMetadata metadata(String world, List<WorldMetadata.Commit> commits) {
        var last = commits.getLast();
        return new WorldMetadata(world, "世界".repeat(32), "2026-11-01T00:00:00Z",
            new WorldMetadata.State(last.epoch(), WorldPhase.FREE, null, null, last.version(), last.committedAt(), null),
            List.of(), commits);
    }

    private Seed seed(Path directory) throws Exception {
        String world = id();
        Path worldRoot = Files.createDirectories(directory.resolve(world));
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("level.dat"), "world-data");
        byte[] manifest = SnapshotFiles.scan(source).encode();
        String hash = SnapshotManifest.hash(manifest);
        List<WorldMetadata.Commit> commits = new ArrayList<>();
        for (int version = 1; version <= 3000; version++) {
            var owner = new WorldMetadata.Owner(USER, id(), id(), id());
            String at = version == 1 ? "2026-11-06T10:00:00Z" : version == 2 ? "2026-10-01T10:00:00Z" : "2026-11-08T09:00:00Z";
            commits.add(new WorldMetadata.Commit(id(), owner, version, version == 1 ? null : (long) version - 1,
                version, hash, at));
        }
        int low = 1, high = commits.size();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metadata(world, commits.subList(0, middle)).toJson().getBytes(StandardCharsets.UTF_8).length <= WorldStore.MAX_METADATA_BYTES)
                low = middle;
            else high = middle - 1;
        }
        assertTrue(low < commits.size());
        var metadata = metadata(world, commits.subList(0, low));
        Files.writeString(worldRoot.resolve("world.json"), metadata.toJson());
        var result = new Seed(directory, world, metadata, hash, manifest);
        for (var commit : List.of(metadata.commits().getFirst(), metadata.commits().get(1), metadata.commits().getLast())) {
            payload(result.snapshot(commit.uploadId()), result);
            Files.setLastModifiedTime(result.snapshot(commit.uploadId()), FileTime.from(NOW.minus(Duration.ofHours(48))));
        }
        return result;
    }

    private static void payload(Path path, Seed seed) throws IOException {
        Files.createDirectories(path.resolve("world"));
        Files.writeString(path.resolve("world/level.dat"), "world-data");
        Files.write(path.resolve("manifest.bin"), seed.manifest());
    }

    private static final class Upload {

        final String upload = id();
        WorldStore.Identity owner;
        Long base;

        long publish(WorldStore store, Seed seed) throws Exception {
            if (owner == null) {
                String device = id();
                var acquired = store.acquire(seed.world(), USER, device, id()).view();
                owner = new WorldStore.Identity(USER, device, acquired.sessionId(), acquired.epoch());
                base = acquired.baseVersion();
            }
            store.stop(seed.world(), owner, WorldStore.StopStage.BEGIN, base);
            store.prepareTransfer(seed.world(), owner, base, true);
            Path candidate = store.candidateDirectory(seed.world(), owner).resolve("payload");
            payload(candidate, seed);
            return store.publishSnapshot(seed.world(), owner, base, upload, seed.hash(), candidate);
        }

    }

    /** 达到 UTF-8 字节上限后淘汰最旧回执，版本仍递增，重启后近期提交重试及继续接管有效。 */
    @Test
    void rollsReceiptsWithinByteLimitAndContinuesAfterRestart() throws Exception {
        var seed = seed(root);
        var first = seed.original().commits().getFirst();
        var old = new WorldStore.Identity(first.owner().username(), first.owner().deviceId(), first.owner().sessionId(), first.epoch());
        var upload = new Upload();
        try (var store = open(seed)) {
            assertEquals(seed.version() + 1, upload.publish(store, seed));
            assertTrue(Files.size(seed.metadata()) <= WorldStore.MAX_METADATA_BYTES);
            var retained = WorldMetadata.parse(Files.readString(seed.metadata()));
            assertTrue(retained.commits().getFirst().version() > 1);
            assertEquals(upload.upload, retained.commits().getLast().uploadId());
            assertEquals(seed.version() + 1, store.committedResult(seed.world(), upload.owner, upload.upload, seed.hash(), upload.base));
            assertNull(store.committedResult(seed.world(), old, first.uploadId(), first.manifestHash(), first.baseVersion()));
            assertTrue(Files.exists(seed.snapshot(first.uploadId())));
            assertEquals(SnapshotReference.from(first), SnapshotReference.read(seed.index(first.uploadId())));
        }
        try (var store = open(seed)) {
            var next = new Upload();
            assertEquals(seed.version() + 2, next.publish(store, seed));
            assertEquals(seed.version() + 1, store.committedResult(seed.world(), upload.owner, upload.upload, seed.hash(), upload.base));
            assertTrue(Files.size(seed.metadata()) <= WorldStore.MAX_METADATA_BYTES);
            assertThrows(WorldAccessException.class,
                () -> store.publishSnapshot(seed.world(), old, first.baseVersion(), first.uploadId(), seed.hash(), root.resolve("unused")));
        }
    }

    /** 被淘汰回执对应的每日备份仍参与保留策略，过期和空间淘汰时同步清理其最小索引。 */
    @Test
    void preservesDailyBackupsAndReclaimsIndexedSnapshotsUnderPressure() throws Exception {
        var seed = seed(root);
        var daily = seed.original().commits().getFirst();
        var expired = seed.original().commits().get(1);
        String current;
        try (var store = open(seed)) {
            Upload upload;
            do {
                upload = new Upload();
                upload.publish(store, seed);
            } while (WorldMetadata.parse(Files.readString(seed.metadata())).commits().getFirst().version() <= 2);
            current = upload.upload;
        }
        var policy = new RetentionConfig(1, 7, 24, 1, 60, ZoneOffset.UTC);
        try (var store = open(seed)) {
            try (var retention = new WorldRetention(store, root.resolve("transfers"), policy, CLOCK, ignored -> Long.MAX_VALUE)) {
                retention.sweep();
                assertTrue(Files.exists(seed.snapshot(daily.uploadId())));
                assertTrue(Files.exists(seed.index(daily.uploadId())));
                assertFalse(Files.exists(seed.snapshot(expired.uploadId())));
                assertFalse(Files.exists(seed.index(expired.uploadId())));
            }
            try (var retention = new WorldRetention(store, root.resolve("transfers"), policy, CLOCK,
                ignored -> Files.exists(seed.snapshot(daily.uploadId())) ? 0 : Long.MAX_VALUE)) {
                retention.ensureSpace(seed.directory(), 1);
                assertFalse(Files.exists(seed.snapshot(daily.uploadId())));
                assertFalse(Files.exists(seed.index(daily.uploadId())));
                assertTrue(Files.exists(seed.snapshot(current)));
            }
        }
    }

    /** 备份索引无法落盘时不淘汰回执、不发布新快照；修复存储后可重试原上传。 */
    @Test
    void indexFailureKeepsAuthorityAndReceiptsUntilRetrySucceeds() throws Exception {
        var seed = seed(root);
        Path blocker = Files.writeString(seed.directory().resolve("snapshot-index"), "blocked");
        var upload = new Upload();
        try (var store = open(seed)) {
            assertThrows(IOException.class, () -> upload.publish(store, seed));
            var metadata = WorldMetadata.parse(Files.readString(seed.metadata()));
            assertEquals(seed.version(), metadata.state().committedVersion());
            assertEquals(1, metadata.commits().getFirst().version());
            assertTrue(Files.exists(seed.snapshot(seed.original().commits().getLast().uploadId())));
            Files.delete(blocker);
            assertEquals(seed.version() + 1, upload.publish(store, seed));
            assertEquals("FREE", store.list().getFirst().status());
        }
    }

    /** 回执淘汰后的元数据发布结果不确定时停止授予所有权，重启按磁盘结果安全确认或重试。 */
    @Test
    void recoversCompactedCommitAfterUncertainMetadataPublication() throws Exception {
        for (boolean moved : List.of(false, true)) {
            var seed = seed(root.resolve(Boolean.toString(moved)));
            var upload = new Upload();
            var fail = new AtomicBoolean(true);
            try (var store = WorldStore.open(seed.store(), CommitReceiptRetentionTest::move, UUID::randomUUID,
                (source, target) -> {
                    boolean committing = Files.readString(source).contains("\"committedVersion\":" + (seed.version() + 1));
                    if (committing && fail.getAndSet(false)) {
                        if (moved) move(source, target);
                        throw new IOException("uncertain publication");
                    }
                    move(source, target);
                }, System::nanoTime, 10, 30, CLOCK)) {
                assertThrows(IOException.class, () -> upload.publish(store, seed));
                assertEquals("RECOVERY_REQUIRED", store.list().getFirst().status());
                assertThrows(WorldAccessException.class, () -> store.acquire(seed.world(), "Other", id(), id()));
            }
            try (var store = open(seed)) {
                Long committed = store.committedResult(seed.world(), upload.owner, upload.upload, seed.hash(), upload.base);
                if (moved) assertEquals(seed.version() + 1, committed);
                else {
                    assertNull(committed);
                    assertEquals(seed.version() + 1, upload.publish(store, seed));
                }
                assertTrue(Files.size(seed.metadata()) <= WorldStore.MAX_METADATA_BYTES);
                assertTrue(Files.exists(seed.snapshot(seed.original().commits().getFirst().uploadId())));
            }
        }
    }

}