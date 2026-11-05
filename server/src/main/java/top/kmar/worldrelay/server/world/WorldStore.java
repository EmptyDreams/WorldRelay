package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.protocol.HostEndpoint;
import top.kmar.worldrelay.server.protocol.OwnershipView;
import top.kmar.worldrelay.server.protocol.WorldSummary;
import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 唯一的元数据写入入口；退出、超时和上传完成声明均不会释放所有权。 */
public final class WorldStore implements AutoCloseable {

    // 保留取消审计和获取请求墓碑；达到上限时拒绝取消，不丢弃历史或释放所有权。
    static final int MAX_METADATA_BYTES = 1024 * 1024;
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final DirectoryPublisher publisher;
    private final DirectoryPublisher metadataPublisher;
    private final Supplier<UUID> idGenerator;
    private final LongSupplier ticker;
    private final int heartbeatIntervalSeconds;
    private final int heartbeatTimeoutSeconds;
    private final long heartbeatTimeoutNanos;
    private final Map<String, Entry> worlds = new TreeMap<>();
    private final Set<String> names = new HashSet<>();
    private boolean closed;

    public enum ReadyStage {SAVE_SYNCED, PORT_OPENED}

    public enum StopStage {BEGIN, UPLOAD_COMPLETE}

    public record Identity(String username, String deviceId, String sessionId, long epoch) {

        public Identity {
            UserStore.validateUsername(username);
            validateId(deviceId);
            validateId(sessionId);
            if (epoch < 1) throw new IllegalArgumentException("Invalid epoch");
        }

    }

    public record Acquisition(boolean acquired, OwnershipView view) {

    }

    private static final class Entry {

        WorldMetadata metadata;
        boolean seen;
        long lastSeen;
        boolean joinReady;
        Integer players;
        boolean unavailable;

        Entry(WorldMetadata metadata) {
            this.metadata = metadata;
        }

    }

    private WorldStore(
        Path root, FileChannel channel, FileLock fileLock, DirectoryPublisher publisher,
        Supplier<UUID> idGenerator, DirectoryPublisher metadataPublisher, LongSupplier ticker,
        int interval, int timeout
    ) {
        this.root = root;
        this.lockChannel = channel;
        this.fileLock = fileLock;
        this.publisher = publisher;
        this.idGenerator = idGenerator;
        this.metadataPublisher = metadataPublisher;
        this.ticker = ticker;
        this.heartbeatIntervalSeconds = interval;
        this.heartbeatTimeoutSeconds = timeout;
        this.heartbeatTimeoutNanos = timeout * 1_000_000_000L;
    }

    public static WorldStore open(Path directory) throws IOException {
        return open(directory, 10, 30);
    }

    public static WorldStore open(Path directory, int interval, int timeout) throws IOException {
        return open(directory, WorldStore::atomicMove, UUID::randomUUID, WorldStore::atomicMove,
            System::nanoTime, interval, timeout);
    }

    static WorldStore open(Path directory, DirectoryPublisher publisher) throws IOException {
        return open(directory, publisher, UUID::randomUUID);
    }

    static WorldStore open(Path directory, DirectoryPublisher publisher, Supplier<UUID> idGenerator) throws IOException {
        return open(directory, publisher, idGenerator, WorldStore::atomicMove, System::nanoTime, 10, 30);
    }

    // 显式注入发布器和单调时钟，验证不确定写盘结果及超时，无需真实等待或反射。
    static WorldStore open(
        Path directory, DirectoryPublisher publisher, Supplier<UUID> idGenerator,
        DirectoryPublisher metadataPublisher, LongSupplier ticker, int interval, int timeout
    ) throws IOException {
        if (interval < 1 || timeout <= interval) throw new IllegalArgumentException("Invalid heartbeat timing");
        Objects.requireNonNull(ticker);
        Files.createDirectories(directory.toAbsolutePath().normalize());
        Path root = directory.toRealPath();
        Path lockFile = root.resolve(".lock");
        if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) requireLocalPath(lockFile, false);
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("World store is already in use");
            }
            if (lock == null) throw new IOException("World store is already in use");
            WorldStore store = new WorldStore(root, channel, lock, publisher, idGenerator, metadataPublisher,
                ticker, interval, timeout);
            store.load();
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public synchronized List<WorldSummary> list() {
        ensureOpen();
        return worlds.values().stream().map(this::summary).toList();
    }

    /** 创建、原子发布和内存更新在同一个同步边界中。 */
    public synchronized WorldSummary create(String name) throws IOException {
        ensureOpen();
        validateName(name);
        if (names.contains(name)) return null;
        String id;
        do {
            id = idGenerator.get().toString();
        }
        while (worlds.containsKey(id) || Files.exists(root.resolve(id), LinkOption.NOFOLLOW_LINKS));
        Entry entry = new Entry(new WorldMetadata(id, name, Instant.now().toString(), WorldMetadata.State.free()));
        Path temporary = Files.createTempDirectory(root, ".creating-");
        try {
            writeForced(temporary.resolve("world.json"), entry.metadata.toJson(), StandardOpenOption.CREATE_NEW);
            publisher.publish(temporary, root.resolve(id));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary.resolve("world.json"));
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        worlds.put(id, entry);
        names.add(name);
        return summary(entry);
    }

    public synchronized Acquisition acquire(String worldId, String username, String deviceId, String requestId)
        throws IOException, WorldAccessException {
        UserStore.validateUsername(username);
        validateId(deviceId);
        validateId(requestId);
        Entry entry = entry(worldId);
        var state = entry.metadata.state();
        for (var committed : entry.metadata.commits()) {
            var owner = committed.owner();
            if (owner.username().equals(username) && owner.deviceId().equals(deviceId) && owner.requestId().equals(requestId)) {
                throw conflict("ACQUISITION_COMPLETED", "Acquisition has already committed");
            }
        }
        for (var cancelled : entry.metadata.cancellations()) {
            var owner = cancelled.owner();
            if (owner.username().equals(username) && owner.deviceId().equals(deviceId) && owner.requestId().equals(requestId)) {
                throw conflict("ACQUISITION_CANCELLED", "Acquisition was cancelled");
            }
        }
        if (state.owner() != null) {
            var owner = state.owner();
            if (owner.username().equals(username) && owner.deviceId().equals(deviceId) && owner.requestId().equals(requestId)) {
                return new Acquisition(false, view(entry));
            }
            throw conflict("WORLD_OWNED", "World already has an owner");
        }
        if (state.epoch() == Long.MAX_VALUE) throw conflict("EPOCH_EXHAUSTED", "World epoch is exhausted");
        var owner = new WorldMetadata.Owner(username, deviceId, UUID.randomUUID().toString(), requestId);
        persist(entry, new WorldMetadata.State(state.epoch() + 1, WorldPhase.PREPARING, owner,
            state.committedVersion(), state.committedVersion(), state.lastCommittedAt(), null));
        touch(entry);
        return new Acquisition(true, view(entry));
    }

    public synchronized OwnershipView heartbeat(String worldId, Identity identity, Integer players) throws WorldAccessException {
        if (players != null && players < 0) throw new IllegalArgumentException("Invalid player count");
        Entry entry = owned(worldId, identity);
        touch(entry);
        if (players != null) entry.players = players;
        return view(entry);
    }

    /** 仅取消尚未开始生成的首次创建；与 ready 共用同步边界。 */
    public synchronized void cancelCreation(String worldId, Identity identity) throws IOException, WorldAccessException {
        Entry entry = entry(worldId);
        for (var cancellation : entry.metadata.cancellations()) {
            if (cancellation.matches(identity)) return; // 不影响后来取得的所有权。
        }
        entry = owned(worldId, identity);
        var state = entry.metadata.state();
        if (state.phase() != WorldPhase.PREPARING || state.committedVersion() != null) {
            throw conflict("INVALID_PHASE", "Only an unstarted new world can be cancelled");
        }
        if (state.epoch() == Long.MAX_VALUE) throw conflict("EPOCH_EXHAUSTED", "World epoch is exhausted");
        var history = new ArrayList<>(entry.metadata.cancellations());
        history.add(new WorldMetadata.Cancellation(state.owner(), state.epoch(), Instant.now().toString()));
        var free = new WorldMetadata.State(state.epoch() + 1, WorldPhase.FREE, null, null, null, null, null);
        persist(entry, new WorldMetadata(entry.metadata.worldId(), entry.metadata.name(), entry.metadata.createdAt(), free, history));
        entry.seen = false;
        entry.joinReady = false;
        entry.players = null;
    }

    public synchronized OwnershipView ready(
        String worldId, Identity identity, ReadyStage stage,
        Long baseVersion, HostEndpoint endpoint
    ) throws IOException, WorldAccessException {
        Objects.requireNonNull(stage);
        Entry entry = owned(worldId, identity);
        var state = entry.metadata.state();
        if (state.phase() == WorldPhase.STOPPING || state.phase() == WorldPhase.COMMITTING) {
            throw conflict("INVALID_PHASE", "World is already stopping");
        }
        if (stage == ReadyStage.SAVE_SYNCED) {
            if (endpoint != null) throw new IllegalArgumentException("Unexpected endpoint");
            checkBase(state, baseVersion);
            if (state.phase() == WorldPhase.PREPARING) persist(entry, state.transition(WorldPhase.STARTING, null));
            // STARTING/RUNNING 的迟到重试不改变阶段，也不恢复端点。
            touch(entry);
        } else {
            Objects.requireNonNull(endpoint);
            if (state.phase() != WorldPhase.STARTING && state.phase() != WorldPhase.RUNNING) {
                throw conflict("INVALID_PHASE", "Save must be ready before opening the port");
            }
            if (state.phase() == WorldPhase.RUNNING && !endpoint.equals(state.endpoint())) {
                throw conflict("ENDPOINT_CONFLICT", "Host endpoint differs from the registered endpoint");
            }
            if (state.phase() == WorldPhase.STARTING) persist(entry, state.transition(WorldPhase.RUNNING, endpoint));
            touch(entry);
            entry.joinReady = true;
        }
        return view(entry);
    }

    public synchronized OwnershipView stop(String worldId, Identity identity, StopStage stage, Long baseVersion)
        throws IOException, WorldAccessException {
        Objects.requireNonNull(stage);
        Entry entry = owned(worldId, identity);
        var state = entry.metadata.state();
        boolean stopping = state.phase() == WorldPhase.STOPPING || state.phase() == WorldPhase.COMMITTING;
        if (stage == StopStage.BEGIN) {
            if (!stopping) {
                persist(entry, state.transition(WorldPhase.STOPPING, null));
            }
        } else {
            checkBase(state, baseVersion);
            if (!stopping) {
                throw conflict("INVALID_PHASE", "Stop must begin before upload completion");
            }
            // 这里只持久化客户端上传完成的声明，没有校验、发布或解锁副作用。
            if (state.phase() == WorldPhase.STOPPING) persist(entry, state.transition(WorldPhase.COMMITTING, null));
        }
        touch(entry);
        entry.joinReady = false;
        return view(entry);
    }

    private Entry entry(String worldId) throws WorldAccessException {
        ensureOpen();
        validateId(worldId);
        Entry entry = worlds.get(worldId);
        if (entry == null) throw new WorldAccessException(404, "WORLD_NOT_FOUND", "World not found");
        if (entry.unavailable) throw new WorldAccessException(503, "WORLD_STATE_UNAVAILABLE", "World state requires server recovery");
        return entry;
    }

    public record SnapshotSource(Path directory, String manifestHash) {

    }

    /** 只在很短的同步边界中检查任期；复制、摘要计算不持有全局所有权锁。 */
    public synchronized SnapshotSource prepareTransfer(String worldId, Identity identity, Long baseVersion, boolean upload)
        throws IOException, WorldAccessException {
        Entry entry = owned(worldId, identity);
        var state = entry.metadata.state();
        checkBase(state, baseVersion);
        if (upload) {
            if (state.phase() != WorldPhase.STOPPING && state.phase() != WorldPhase.COMMITTING) {
                throw conflict("INVALID_PHASE", "World must be stopped before upload");
            }
            if (state.phase() == WorldPhase.STOPPING) persist(entry, state.transition(WorldPhase.COMMITTING, null));
        } else if (state.phase() != WorldPhase.PREPARING && state.phase() != WorldPhase.STARTING) {
            throw conflict("INVALID_PHASE", "World cannot be downloaded in this phase");
        }
        touch(entry);
        if (baseVersion == null) return null;
        if (entry.metadata.commits().isEmpty()) throw conflict("SNAPSHOT_UNAVAILABLE", "Committed snapshot is unavailable");
        var receipt = entry.metadata.commits().getLast();
        Path path = root.resolve(worldId).resolve("snapshots").resolve(receipt.uploadId());
        requireLocalPath(path, true);
        return new SnapshotSource(path, receipt.manifestHash());
    }

    /** 响应丢失后即使已有下一位房主，也能用原身份和原摘要查询幂等结果。 */
    public synchronized Long committedResult(String worldId, Identity identity, String uploadId, String hash, Long base)
        throws WorldAccessException {
        validateId(uploadId);
        for (var receipt : entry(worldId).metadata.commits()) {
            if (!receipt.uploadId().equals(uploadId)) continue;
            if (!receipt.matches(identity)) throw new WorldAccessException(403, "OWNER_MISMATCH", "Commit identity does not match");
            if (!receipt.manifestHash().equals(hash) || !Objects.equals(receipt.baseVersion(), base)) {
                throw conflict("COMMIT_CONFLICT", "Commit request differs from published snapshot");
            }
            return receipt.version();
        }
        return null;
    }

    public synchronized Path candidateDirectory(String worldId, Identity identity) throws IOException, WorldAccessException {
        owned(worldId, identity);
        return Files.createTempDirectory(root.resolve(worldId), ".candidate-");
    }

    /** candidate 由协调服务私有持有，SSH 从未获得此路径；最终元数据原子发布同时释放所有权。 */
    public synchronized long publishSnapshot(
        String worldId, Identity identity, Long baseVersion,
        String uploadId, String hash, Path candidate
    ) throws IOException, WorldAccessException {
        Long completed = committedResult(worldId, identity, uploadId, hash, baseVersion);
        if (completed != null) return completed;
        Entry entry = owned(worldId, identity);
        var state = entry.metadata.state();
        checkBase(state, baseVersion);
        if (state.phase() != WorldPhase.COMMITTING) throw conflict("INVALID_PHASE", "Upload has not begun");
        long version = baseVersion == null ? 1 : Math.addExact(baseVersion, 1);
        var commits = new ArrayList<>(entry.metadata.commits());
        commits.add(new WorldMetadata.Commit(uploadId, state.owner(), state.epoch(), baseVersion, version, hash));
        var free = new WorldMetadata.State(state.epoch(), WorldPhase.FREE, null, null, version, Instant.now().toString(), null);
        var next = new WorldMetadata(worldId, entry.metadata.name(), entry.metadata.createdAt(), free, entry.metadata.cancellations(), commits);
        // 先确认元数据不会因审计历史超过上限而失败。
        if (next.toJson().getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) throw new IOException("World metadata exceeds limit");
        Path world = root.resolve(worldId);
        if (!candidate.getParent().getParent().equals(world) || !candidate.getParent().getFileName().toString().startsWith(".candidate-")) {
            throw new IOException("Candidate must be private to world store");
        }
        Path snapshots = world.resolve("snapshots");
        Files.createDirectories(snapshots);
        requireLocalPath(snapshots, true);
        Path target = snapshots.resolve(uploadId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // 上次可能在快照落盘后、元数据提交前崩溃。旧候选只能按完全相同清单复用。
            var manifest = top.kmar.worldrelay.snapshot.SnapshotManifest.read(target.resolve("manifest.bin"), hash);
            SnapshotFiles.verify(target.resolve("world"), manifest);
        } else Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE);
        SnapshotFiles.forceDirectory(snapshots);
        SnapshotFiles.forceDirectory(world);
        persist(entry, next);
        entry.seen = false;
        entry.joinReady = false;
        entry.players = null;
        return version;
    }

    private Entry owned(String worldId, Identity identity) throws WorldAccessException {
        Objects.requireNonNull(identity);
        Entry entry = entry(worldId);
        var state = entry.metadata.state();
        var owner = state.owner();
        if (owner == null) throw conflict("WORLD_NOT_OWNED", "World has no owner");
        if (!owner.username().equals(identity.username()) || !owner.deviceId().equals(identity.deviceId())) {
            throw new WorldAccessException(403, "OWNER_MISMATCH", "Request does not belong to the owner");
        }
        if (state.epoch() != identity.epoch()) throw conflict("STALE_EPOCH", "Ownership epoch does not match");
        if (!owner.sessionId().equals(identity.sessionId())) throw conflict("SESSION_MISMATCH", "Ownership session does not match");
        return entry;
    }

    private static void checkBase(WorldMetadata.State state, Long version) throws WorldAccessException {
        if (!Objects.equals(state.baseVersion(), version)) throw conflict("BASE_VERSION_MISMATCH", "Base version does not match");
    }

    private static WorldAccessException conflict(String code, String message) {
        return new WorldAccessException(409, code, message);
    }

    private void touch(Entry entry) {
        long now = ticker.getAsLong();
        online(entry, now); // 超时后旧端点不能靠心跳恢复。
        entry.seen = true;
        entry.lastSeen = now;
    }

    private boolean online(Entry entry, long now) {
        if (!entry.seen || now - entry.lastSeen >= heartbeatTimeoutNanos) {
            entry.joinReady = false;
            entry.players = null;
            return false;
        }
        return true;
    }

    private String status(Entry entry) {
        var state = entry.metadata.state();
        if (entry.unavailable) return "RECOVERY_REQUIRED";
        if (state.owner() != null && (!online(entry, ticker.getAsLong())
            || (state.phase() == WorldPhase.RUNNING && !entry.joinReady))) return "RECOVERY_REQUIRED";
        return state.phase().name();
    }

    private WorldSummary summary(Entry entry) {
        var metadata = entry.metadata;
        var state = metadata.state();
        String status = status(entry);
        return new WorldSummary(metadata.worldId(), metadata.name(), status,
            state.owner() == null ? null : state.owner().username(),
            state.owner() == null && !entry.unavailable ? Integer.valueOf(0) : entry.players, state.lastCommittedAt(),
            status.equals("RUNNING") ? state.endpoint() : null);
    }

    private OwnershipView view(Entry entry) {
        var state = entry.metadata.state();
        String status = status(entry);
        return new OwnershipView(entry.metadata.worldId(), state.epoch(), state.owner().sessionId(), state.phase(), status,
            state.baseVersion(), state.committedVersion(), status.equals("RUNNING") ? state.endpoint() : null,
            heartbeatIntervalSeconds, heartbeatTimeoutSeconds);
    }

    private void persist(Entry entry, WorldMetadata.State state) throws IOException {
        persist(entry, entry.metadata.withState(state));
    }

    private void persist(Entry entry, WorldMetadata next) throws IOException {
        Path directory = root.resolve(next.worldId());
        Path target = directory.resolve("world.json");
        try {
            requireLocalPath(directory, true);
            requireLocalPath(target, false);
        } catch (IOException failure) {
            entry.unavailable = true;
            throw failure;
        }
        Path temporary = Files.createTempFile(directory, ".world-", ".tmp");
        try {
            writeForced(temporary, next.toJson(), StandardOpenOption.TRUNCATE_EXISTING);
            try {
                metadataPublisher.publish(temporary, target);
                SnapshotFiles.forceDirectory(directory);
            } catch (IOException | RuntimeException | Error failure) {
                // 原子移动的异常可能意味着结果不确定，不能继续用旧内存授予或修改所有权。
                entry.unavailable = true;
                entry.joinReady = false;
                entry.players = null;
                throw failure;
            }
            entry.metadata = next;
        } finally {
            // 发布成功后的临时路径已消失；清理失败不能掩盖已完成的元数据提交。
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) { /* 遗留临时文件不参与加载。 */ }
        }
    }

    private static void writeForced(Path path, String json, StandardOpenOption option) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) throw new IOException("World metadata exceeds size limit");
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.WRITE, option, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                //noinspection ResultOfMethodCallIgnored
                output.write(buffer);
            }
            output.force(true);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void load() throws IOException {
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                String id = directory.getFileName().toString();
                if (isNotId(id)) continue;
                requireLocalPath(directory, true);
                Path file = directory.resolve("world.json");
                requireLocalPath(file, false);
                byte[] bytes;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
                }
                if (bytes.length > MAX_METADATA_BYTES) throw new IOException("World metadata exceeds size limit: " + id);
                try {
                    String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                    WorldMetadata metadata = WorldMetadata.parse(json);
                    if (!id.equals(metadata.worldId())) throw new IllegalArgumentException("World ID mismatch");
                    if (!names.add(metadata.name())) throw new IOException("Duplicate world name in metadata: " + id);
                    worlds.put(id, new Entry(metadata)); // 从不恢复在线状态或可加入标记。
                } catch (IllegalArgumentException | DateTimeException | NullPointerException failure) {
                    throw new IOException("Invalid world metadata: " + id);
                }
            }
        }
    }

    /** 拒绝符号链接、junction 或指向不同位置的路径。 */
    private static void requireLocalPath(Path path, boolean directory) throws IOException {
        boolean correctType = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!correctType || Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) {
            throw new IOException("World store path must be a local " + (directory ? "directory" : "file") + ": " + path);
        }
    }

    public static void validateName(String name) {
        if (name == null || name.isBlank() || !name.equals(name.strip()) || name.codePointCount(0, name.length()) > 64
            || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.SURROGATE)) {
            throw new IllegalArgumentException("Invalid world name");
        }
    }

    public static void validateId(String id) {
        if (id == null || isNotId(id)) throw new IllegalArgumentException("Expected a canonical UUID");
    }

    private static boolean isNotId(String id) {
        return !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("World store is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            fileLock.release();
        } finally {
            lockChannel.close();
        }
    }

    @FunctionalInterface
    interface DirectoryPublisher {

        void publish(Path source, Path target) throws IOException;

    }

}