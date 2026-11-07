package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/** 管理端恢复只复制已提交快照；失败保留原权威版本，成功以更高版本和任期原子发布。 */
public final class WorldBackups {

    private final WorldStore worlds;
    private final WorldRetention retention;

    public record Restore(String worldId, String restoreId, long backupVersion, long expectedVersion, long expectedEpoch, String manifestHash) {

        public Restore {
            WorldStore.validateId(worldId);
            WorldStore.validateId(restoreId);
            if (backupVersion < 1 || backupVersion > expectedVersion || expectedVersion == Long.MAX_VALUE
                || expectedEpoch < 0 || expectedEpoch == Long.MAX_VALUE || manifestHash == null || !manifestHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid restore request");
        }

        public void writeJson(JsonWriter writer, boolean confirm) {
            writer.beginObject().name("worldId").value(worldId).name("restoreId").value(restoreId)
                .name("backupVersion").value(backupVersion).name("expectedVersion").value(expectedVersion)
                .name("expectedEpoch").value(expectedEpoch).name("manifestHash").value(manifestHash)
                .name("confirm").value(confirm).endObject();
        }

    }

    public record Result(String worldId, String restoreId, long backupVersion, long previousVersion, long version, long epoch, String committedAt) {

        public String toJson() {
            return new JsonWriter().beginObject().name("worldId").value(worldId).name("restoreId").value(restoreId)
                .name("backupVersion").value(backupVersion).name("previousVersion").value(previousVersion)
                .name("version").value(version).name("epoch").value(epoch).name("committedAt").value(committedAt).endObject().finish();
        }

    }

    private record Backup(SnapshotReference reference, Path directory, String hash) {

    }

    public WorldBackups(WorldStore worlds, WorldRetention retention) {
        this.worlds = Objects.requireNonNull(worlds);
        this.retention = Objects.requireNonNull(retention);
    }

    private List<Backup> backups(WorldMetadata metadata) throws IOException {
        List<Backup> result = new ArrayList<>();
        for (var reference : worlds.snapshotReferences(metadata)) {
            Path directory = worlds.directory().resolve(metadata.worldId()).resolve("snapshots").resolve(reference.uploadId());
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            SnapshotFiles.requireDirectory(directory);
            String hash = metadata.commits().stream().filter(commit -> commit.uploadId().equals(reference.uploadId()))
                .map(WorldMetadata.Commit::manifestHash).findFirst().orElse(reference.manifestHash());
            String at = reference.committedAt();
            if (at == null) at = reference.version() == metadata.state().committedVersion() ? metadata.state().lastCommittedAt()
                : Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant().toString();
            result.add(new Backup(new SnapshotReference(reference.uploadId(), reference.version(), at), directory, hash));
        }
        result.sort(Comparator.comparingLong((Backup backup) -> backup.reference().version()).reversed());
        return result;
    }

    public String list(String worldId) throws IOException, WorldAccessException {
        var state = worlds.backupState(worldId);
        var metadata = state.metadata();
        var writer = new JsonWriter().beginObject().name("worldId").value(worldId).name("currentVersion");
        if (metadata.state().committedVersion() == null) writer.nullValue();
        else writer.value(metadata.state().committedVersion());
        writer.name("epoch").value(metadata.state().epoch()).name("canRestore").value(state.canRestore()).name("backups").beginArray();
        for (var backup : backups(metadata)) {
            var reference = backup.reference();
            writer.beginObject().name("version").value(reference.version()).name("committedAt").value(reference.committedAt())
                .name("current").value(Objects.equals(reference.version(), metadata.state().committedVersion())).endObject();
        }
        return writer.endArray().endObject().finish();
    }

    public Restore preview(String worldId, long backupVersion) throws IOException, WorldAccessException {
        var state = worlds.backupState(worldId);
        if (!state.canRestore()) throw new WorldAccessException(409, "WORLD_NOT_FREE", "World must be unowned before restoring a backup");
        Backup source = find(state.metadata(), backupVersion);
        byte[] bytes;
        try (var input = Files.newInputStream(source.directory().resolve(SnapshotManifest.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(SnapshotManifest.MAX_MANIFEST_BYTES + 1);
        }
        if (bytes.length > SnapshotManifest.MAX_MANIFEST_BYTES) throw new IOException("Backup manifest exceeds size limit");
        String hash = SnapshotManifest.hash(bytes);
        if (source.hash() != null && !source.hash().equals(hash)) throw new IOException("Backup manifest checksum mismatch");
        SnapshotManifest.decode(bytes);
        return new Restore(worldId, UUID.randomUUID().toString(), backupVersion, state.metadata().state().committedVersion(), state.metadata().state().epoch(), hash);
    }

    private Backup find(WorldMetadata metadata, long version) throws IOException, WorldAccessException {
        return backups(metadata).stream().filter(backup -> backup.reference().version() == version).findFirst()
            .orElseThrow(() -> new WorldAccessException(404, "BACKUP_NOT_FOUND", "Backup is no longer retained"));
    }

    public void authorize(Restore request) throws WorldAccessException {
        worlds.checkRestore(request);
    }

    @SuppressWarnings({"try", "unused"}) // 生命周期保护阻止清理活动候选；源快照另由 WorldStore 固定。
    public Result restore(Restore request) throws IOException, WorldAccessException {
        try (var operation = retention.protect(request.worldId())) {
            Result completed = worlds.restoreResult(request);
            if (completed != null) return completed;
            var source = find(worlds.backupState(request.worldId()).metadata(), request.backupVersion());
            if (source.hash() != null && !source.hash().equals(request.manifestHash()))
                throw new WorldAccessException(409, "RESTORE_CONFLICT", "Backup checksum differs from the restore preview");
            if (!worlds.beginRestore(request, source.reference().uploadId())) return worlds.restoreResult(request);
            try {
                Path manifestFile = source.directory().resolve(SnapshotManifest.FILE_NAME);
                var manifest = SnapshotManifest.read(manifestFile, request.manifestHash());
                long bytes = manifest.entries().stream().mapToLong(SnapshotManifest.Entry::size).sum() + Files.size(manifestFile);
                retention.ensureSpace(worlds.directory(), bytes);
                Path candidate = worlds.restoreCandidate(request);
                SnapshotFiles.copyIsolated(source.directory(), candidate, false);
                try (var entries = Files.list(candidate)) {
                    if (!entries.map(path -> path.getFileName().toString()).sorted().toList().equals(List.of("manifest.bin", "world")))
                        throw new IOException("Unexpected files in backup snapshot");
                }
                SnapshotManifest.read(candidate.resolve(SnapshotManifest.FILE_NAME), request.manifestHash());
                if (Files.exists(candidate.resolve("world/session.lock"), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Backup contains a session lock");
                SnapshotFiles.verify(candidate.resolve("world"), manifest);
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Backup restore interrupted");
                Result result = worlds.publishRestore(request, candidate);
                System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                    "Restored world {0} from backup {1}: previous={2}, published={3}, epoch={4}, restoreId={5}",
                    request.worldId(), request.backupVersion(), result.previousVersion(), result.version(), result.epoch(), request.restoreId());
                retention.requestSweep();
                return result;
            } finally {
                worlds.endRestore(request);
            }
        }
    }

}