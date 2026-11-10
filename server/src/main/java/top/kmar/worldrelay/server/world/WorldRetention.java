package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只回收服务端历史副本；当前权威快照、有效任期工作副本及提交回执不参与淘汰。 */
public final class WorldRetention implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(WorldRetention.class.getName());
    private final WorldStore worlds;
    private final Path transfers;
    private final RetentionConfig config;
    private final Clock clock;
    private final SpaceProbe space;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Map<String, Integer> activeOperations = new HashMap<>();
    private volatile boolean closed;

    @FunctionalInterface
    interface SpaceProbe {

        long usable(Path directory) throws IOException;

    }

    private record Snapshot(String world, SnapshotReference commit, Path path, Instant time) {

    }

    final class Operation implements AutoCloseable {

        private final String world;

        private Operation(String world) {
            this.world = world;
        }

        @Override
        public void close() {
            synchronized (WorldRetention.this) {
                activeOperations.computeIfPresent(world, (id, count) -> count == 1 ? null : count - 1);
            }
        }

    }

    synchronized Operation protect(String world) {
        activeOperations.merge(world, 1, Integer::sum);
        return new Operation(world);
    }

    public WorldRetention(WorldStore worlds, Path transfers, RetentionConfig config) {
        this(worlds, transfers, config, Clock.systemUTC(), path -> Files.getFileStore(path).getUsableSpace());
    }

    WorldRetention(WorldStore worlds, Path transfers, RetentionConfig config, Clock clock, SpaceProbe space) {
        this.worlds = Objects.requireNonNull(worlds);
        this.transfers = transfers.toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.space = Objects.requireNonNull(space);
        worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("worldrelay-retention").factory());
    }

    public void start() {
        worker.scheduleWithFixedDelay(this::backgroundSweep, 0, config.sweepIntervalMinutes(), TimeUnit.MINUTES);
    }

    public void requestSweep() {
        if (closed || !queued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                queued.set(false);
                backgroundSweep();
            });
        } catch (RejectedExecutionException ignored) {
            queued.set(false);
        }
    }

    public synchronized WorldStore.Deletion previewDeletion(String worldId) throws IOException, WorldAccessException {
        if (activeOperations.containsKey(worldId)) throw new WorldAccessException(409, "WORLD_BUSY", "World has an active file operation");
        return worlds.previewDeletion(worldId);
    }

    public synchronized Instant delete(WorldStore.Deletion request) throws IOException, WorldAccessException {
        if (activeOperations.containsKey(request.worldId())) throw new WorldAccessException(409, "WORLD_BUSY", "World has an active file operation");
        return worlds.delete(request);
    }

    public record BackupDeletion(String worldId, String deleteId, long backupVersion, String snapshotId,
                                 long expectedVersion, long expectedEpoch) {

        public BackupDeletion {
            WorldStore.validateId(worldId);
            WorldStore.validateId(deleteId);
            WorldStore.validateId(snapshotId);
            if (backupVersion < 1 || backupVersion >= expectedVersion || expectedEpoch < 0)
                throw new IllegalArgumentException("Only historical backups can be deleted");
        }

        public String toJson(boolean confirm) {
            return new top.kmar.worldrelay.server.json.JsonWriter().beginObject().name("worldId").value(worldId)
                .name("deleteId").value(deleteId).name("backupVersion").value(backupVersion).name("snapshotId").value(snapshotId)
                .name("expectedVersion").value(expectedVersion).name("expectedEpoch").value(expectedEpoch)
                .name("confirm").value(confirm).endObject().finish();
        }

    }

    public synchronized BackupDeletion previewBackupDeletion(String worldId, long version) throws IOException, WorldAccessException {
        if (version < 1) throw new IllegalArgumentException("Invalid backup version");
        if (activeOperations.containsKey(worldId)) throw new WorldAccessException(409, "WORLD_BUSY", "World has an active file operation");
        var state = worlds.backupState(worldId);
        if (!state.canRestore()) throw new WorldAccessException(409, "WORLD_NOT_FREE", "World must be unowned before deleting a backup");
        var metadata = state.metadata();
        if (metadata.state().committedVersion() == null || version >= metadata.state().committedVersion())
            throw new WorldAccessException(409, "BACKUP_PROTECTED", "Current authoritative snapshot cannot be deleted");
        var backup = snapshots(metadata).stream().filter(item -> item.commit().version() == version).findFirst()
            .orElseThrow(() -> new WorldAccessException(404, "BACKUP_NOT_FOUND", "Backup is no longer retained"));
        return new BackupDeletion(worldId, UUID.randomUUID().toString(), version, backup.commit().uploadId(),
            metadata.state().committedVersion(), metadata.state().epoch());
    }

    public synchronized void deleteBackup(BackupDeletion request) throws IOException, WorldAccessException {
        if (activeOperations.containsKey(request.worldId())) throw new WorldAccessException(409, "WORLD_BUSY", "World has an active file operation");
        Path trash = worlds.retireBackup(request);
        if (trash != null) deleteTree(trash);
        LOG.log(System.Logger.Level.INFO, "Deleted backup: world={0}, version={1}, deletion={2}",
            request.worldId(), request.backupVersion(), request.deleteId());
    }

    public void writeStorage(top.kmar.worldrelay.server.json.JsonWriter writer, String worldId) throws WorldAccessException {
        var metadata = worlds.backupState(worldId).metadata();
        try {
            WorldStorage.write(writer, worlds, transfers, metadata);
        } catch (IOException failure) {
            writer.nullValue();
        }
    }

    private void backgroundSweep() {
        try {
            sweep();
        } catch (IOException | RuntimeException failure) {
            if (!closed) LOG.log(System.Logger.Level.WARNING, "World retention cleanup failed", failure);
        }
    }

    /** 清理串行执行，但递归扫描和删除不持有全局所有权锁。 */
    synchronized void sweep() throws IOException {
        if (closed) return;
        cleanTemporary();
        for (var world : worlds.retentionWorlds()) {
            if (world.unavailable()) continue;
            List<Snapshot> snapshots = snapshots(world.metadata());
            Set<String> keep = retained(snapshots);
            for (Snapshot snapshot : snapshots) {
                if (!keep.contains(snapshot.commit().uploadId())) remove(snapshot, false);
            }
        }
        reclaim(worlds.directory(), 0, false);
        if (Files.exists(transfers, LinkOption.NOFOLLOW_LINKS)) reclaim(transfers, 0, false);
    }

    /** 大型私有复制前预留空间；不足时只从同一文件系统回收最旧历史版本。 */
    public synchronized void ensureSpace(Path allocationDirectory, long requiredBytes) throws IOException, WorldAccessException {
        if (requiredBytes < 0) throw new IllegalArgumentException("Negative allocation size");
        if (requiredBytes > Long.MAX_VALUE - config.minimumFreeBytes()
            || space.usable(allocationDirectory) < config.minimumFreeBytes() + requiredBytes) cleanTemporary();
        if (!reclaim(allocationDirectory, requiredBytes, true))
            throw new WorldAccessException(507, "INSUFFICIENT_STORAGE", "Insufficient storage; authoritative snapshot and ownership retained");
    }

    private boolean reclaim(Path allocation, long required, boolean fail) throws IOException {
        SnapshotFiles.requireDirectory(allocation);
        long target;
        try {
            target = Math.addExact(config.minimumFreeBytes(), required);
        } catch (ArithmeticException overflow) {
            throw new IOException("Snapshot size exceeds storage capacity", overflow);
        }
        if (space.usable(allocation) >= target) return true;
        List<Snapshot> candidates = new ArrayList<>();
        FileStore fileStore = Files.getFileStore(allocation);
        for (var world : worlds.retentionWorlds()) {
            if (world.unavailable()) continue;
            List<Snapshot> existing = snapshots(world.metadata());
            for (Snapshot snapshot : existing) {
                if (snapshot.commit().version() != world.metadata().state().committedVersion()
                    && Files.getFileStore(snapshot.path()).equals(fileStore)) candidates.add(snapshot);
            }
        }
        candidates.sort(Comparator.comparing(Snapshot::time).thenComparing(snapshot -> snapshot.path().toString()));
        for (Snapshot snapshot : candidates) {
            remove(snapshot, true);
            if (space.usable(allocation) >= target) return true;
        }
        if (!fail) LOG.log(System.Logger.Level.WARNING, "Storage reserve unavailable; no more safe historical snapshots to evict: {0}", allocation);
        return false;
    }

    private List<Snapshot> snapshots(WorldMetadata metadata) throws IOException {
        Path directory = worlds.directory().resolve(metadata.worldId()).resolve("snapshots");
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        SnapshotFiles.requireDirectory(directory);
        List<Snapshot> result = new ArrayList<>();
        for (var commit : worlds.snapshotReferences(metadata)) {
            Path path = directory.resolve(commit.uploadId());
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                worlds.forgetMissingSnapshotIndex(metadata.worldId(), commit.uploadId());
                continue;
            }
            SnapshotFiles.requireDirectory(path);
            // 老元数据只记录最后提交时间；更早快照使用服务端发布目录的时间。
            Instant at = commit.committedAt() != null ? Instant.parse(commit.committedAt())
                : commit.version() == metadata.state().committedVersion() ? Instant.parse(metadata.state().lastCommittedAt())
                : Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
            result.add(new Snapshot(metadata.worldId(), commit, path, at));
        }
        return result;
    }

    private Set<String> retained(List<Snapshot> snapshots) {
        Set<String> keep = new HashSet<>();
        snapshots.stream().sorted(Comparator.comparingLong((Snapshot snapshot) -> snapshot.commit().version()).reversed())
            .limit(config.recentVersions()).forEach(snapshot -> keep.add(snapshot.commit().uploadId()));
        LocalDate today = clock.instant().atZone(config.timeZone()).toLocalDate();
        LocalDate firstDay = today.minusDays(config.dailyBackupDays());
        Map<LocalDate, Snapshot> days = new HashMap<>();
        for (Snapshot snapshot : snapshots) {
            LocalDate day = snapshot.time().atZone(config.timeZone()).toLocalDate();
            if (!day.isAfter(firstDay) || day.isAfter(today)) continue;
            days.merge(day, snapshot, (left, right) -> left.time().isAfter(right.time())
                || (left.time().equals(right.time()) && left.commit().version() > right.commit().version()) ? left : right);
        }
        days.values().forEach(snapshot -> keep.add(snapshot.commit().uploadId()));
        return keep;
    }

    private void remove(Snapshot snapshot, boolean pressure) throws IOException {
        Path trash = worlds.retireSnapshot(snapshot.world(), snapshot.commit().uploadId());
        if (trash == null) return;
        deleteTree(trash);
        LOG.log(pressure ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
            "Removed {0} snapshot: world={1}, version={2}", pressure ? "oldest backup under storage pressure" : "expired",
            snapshot.world(), snapshot.commit().version());
    }

    private void cleanTemporary() throws IOException {
        Instant cutoff = clock.instant().minus(Duration.ofHours(config.temporaryHours()));
        cleanChildren(worlds.directory(), null, null, false, cutoff, ".creating-", ".deleted-");
        for (var world : worlds.retentionWorlds()) {
            if (world.unavailable() || activeOperations.containsKey(world.metadata().worldId())) continue;
            String id = world.metadata().worldId();
            Path directory = worlds.directory().resolve(id);
            cleanChildren(directory, id, null, false, cutoff, ".candidate-", ".world-");
            cleanChildren(directory.resolve("snapshot-index"), id, null, false, cutoff, ".index-");
            Path snapshots = directory.resolve("snapshots");
            cleanChildren(snapshots, id, null, true, cutoff);
            for (Path path : children(snapshots)) {
                if (isId(path.getFileName().toString())
                    && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    Path trash = worlds.retireTemporary(id, null, path, true);
                    if (trash != null) deleteTree(trash);
                }
            }
        }
        Path sessions = transfers.resolve(".sessions");
        if (Files.notExists(sessions, LinkOption.NOFOLLOW_LINKS)) return;
        SnapshotFiles.requireDirectory(transfers);
        for (Path user : children(sessions)) {
            if (!user.getFileName().toString().matches("[0-9a-f]{64}")) continue;
            for (Path world : children(user)) {
                if (!isId(world.getFileName().toString())) continue;
                if (activeOperations.containsKey(world.getFileName().toString())) continue;
                cleanChildren(world, world.getFileName().toString(), null, true, cutoff);
                for (Path epoch : children(world)) {
                    long number;
                    try {
                        number = Long.parseLong(epoch.getFileName().toString());
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    cleanChildren(epoch, world.getFileName().toString(), number, true, cutoff, "upload-", "download-");
                    for (Path session : children(epoch)) {
                        String name = session.getFileName().toString();
                        if (name.startsWith("upload-") || name.startsWith("download-"))
                            cleanChildren(session, world.getFileName().toString(), number, false, cutoff, ".seed-");
                    }
                    if (children(epoch).isEmpty()) {
                        Path trash = worlds.retireTemporary(world.getFileName().toString(), number, epoch, true);
                        if (trash != null) deleteTree(trash);
                    }
                }
            }
        }
    }

    private void cleanChildren(Path parent, String world, Long epoch, boolean preserveOwned, Instant cutoff, String... prefixes) throws IOException {
        for (Path path : children(parent)) {
            String name = path.getFileName().toString();
            if (name.startsWith(".retention-trash-")) {
                // 改名已经原子撤出引用，重启后可继续完成未结束的删除。
                deleteTree(path);
                continue;
            }
            boolean match = Arrays.stream(prefixes).anyMatch(name::startsWith);
            if (!match || Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(cutoff)) continue;
            Path trash = worlds.retireTemporary(world, epoch, path, preserveOwned);
            if (trash != null) deleteTree(trash);
        }
    }

    private static boolean isId(String text) {
        try {
            WorldStore.validateId(text);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static List<Path> children(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        SnapshotFiles.requireDirectory(directory);
        try (var files = Files.list(directory)) {
            return files.toList();
        }
    }

    /** 不跟随链接；遇到 junction 等别名目录时拒绝递归，避免越出受管目录。 */
    @SuppressWarnings("NullableProblems") // JDK-only 服务端不为文件遍历回调引入外部空值注解。
    private static void deleteTree(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        SnapshotFiles.requireDirectory(absolute.getParent());
        try (var parent = Files.newDirectoryStream(absolute.getParent())) {
            if (parent instanceof SecureDirectoryStream<Path> secure) {
                secureDelete(secure, absolute.getFileName());
                return;
            }
        }
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            private void interrupted() throws InterruptedIOException {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Retention cleanup interrupted");
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                interrupted();
                SnapshotFiles.requireDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                interrupted();
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                interrupted();
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void secureDelete(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Retention cleanup interrupted");
        var attributes = parent.getFileAttributeView(name, java.nio.file.attribute.BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (attributes.isDirectory()) {
            try (var directory = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path entry : directory) {
                    secureDelete(directory, entry.getFileName());
                }
            }
            parent.deleteDirectory(name);
        } else {
            parent.deleteFile(name);
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) LOG.log(System.Logger.Level.WARNING, "Retention worker did not stop in time");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
