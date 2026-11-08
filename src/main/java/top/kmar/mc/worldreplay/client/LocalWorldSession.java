package top.kmar.mc.worldreplay.client;

import java.util.UUID;

/** 会话是恢复记录，不包含 Token。START_REQUESTED 起不允许重新生成或取消创建。 */
record LocalWorldSession(String worldId, String deviceId, String requestId, String sessionId, long epoch,
                         Stage stage, String folder, Long baseVersion, String uploadId, String manifestHash, LocalSource localSource) {

    enum Stage {ACQUIRING, PREPARING, CANCELLING, CANCELLED, DOWNLOADING, START_REQUESTED, STARTED, STOPPING, STOPPED, UPLOADING, COMMITTED}

    record LocalSource(long epoch, String folder, Long baseVersion) {

        LocalSource {
            if (epoch < 1) throw new IllegalArgumentException("Invalid local source epoch");
            if (baseVersion != null && baseVersion < 1) throw new IllegalArgumentException("Invalid local source version");
            validateFolder(folder);
            if (folder == null) throw new IllegalArgumentException("Missing local source folder");
        }

    }

    LocalWorldSession(
        String worldId, String deviceId, String requestId, String sessionId, long epoch,
        Stage stage, String folder, Long baseVersion, String uploadId, String manifestHash
    ) {
        this(worldId, deviceId, requestId, sessionId, epoch, stage, folder, baseVersion, uploadId, manifestHash, null);
    }

    LocalWorldSession(String worldId, String deviceId, String requestId, String sessionId, long epoch, Stage stage, String folder) {
        this(worldId, deviceId, requestId, sessionId, epoch, stage, folder, null, null, null);
    }

    LocalWorldSession {
        for (String id : new String[]{worldId, deviceId, requestId}) {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid session UUID");
        }
        if (stage == null || epoch < 0 || (sessionId == null) != (epoch == 0)) throw new IllegalArgumentException("Invalid session");
        if (sessionId != null && !UUID.fromString(sessionId).toString().equals(sessionId)) throw new IllegalArgumentException("Invalid session UUID");
        if (stage != Stage.ACQUIRING && sessionId == null) throw new IllegalArgumentException("Missing ownership");
        validateFolder(folder);
        if (localSource != null && epoch != 0 && localSource.epoch() >= epoch)
            throw new IllegalArgumentException("Local source is not from an older epoch");
        if ((stage == Stage.START_REQUESTED || stage == Stage.STARTED || stage == Stage.STOPPING) && folder == null) {
            throw new IllegalArgumentException("Missing world folder");
        }
        if (baseVersion != null && baseVersion < 1) throw new IllegalArgumentException("Invalid base version");
        if (uploadId != null && !UUID.fromString(uploadId).toString().equals(uploadId)) throw new IllegalArgumentException("Invalid transfer ID");
        if (manifestHash != null && !manifestHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid manifest hash");
        if ((stage == Stage.STOPPED || stage == Stage.UPLOADING || stage == Stage.COMMITTED || stage == Stage.DOWNLOADING) && folder == null) {
            throw new IllegalArgumentException("Missing world folder");
        }
        if (stage == Stage.UPLOADING && (uploadId == null || manifestHash == null))
            throw new IllegalArgumentException("Missing upload receipt identity");
    }

    LocalWorldSession owned(WorldOwnershipClient.View view) {
        return new LocalWorldSession(worldId, deviceId, requestId, view.sessionId(), view.epoch(), Stage.PREPARING, null, view.baseVersion(), null, null, localSource);
    }

    LocalWorldSession with(Stage next, String target) {
        return new LocalWorldSession(worldId, deviceId, requestId, sessionId, epoch, next, target, baseVersion, uploadId, manifestHash,
            next == Stage.STARTED ? null : localSource);
    }

    LocalWorldSession uploading(String id, String hash) {
        return new LocalWorldSession(worldId, deviceId, requestId, sessionId, epoch, Stage.UPLOADING, folder, baseVersion, id, hash);
    }

    boolean canOpenCreation() {
        return localSource == null && baseVersion == null && stage == Stage.PREPARING;
    }

    boolean canResume() {
        return stage == Stage.START_REQUESTED || stage == Stage.STARTED || stage == Stage.STOPPING;
    }

    boolean canCancel() {
        return localSource == null && baseVersion == null && (stage == Stage.PREPARING || stage == Stage.CANCELLING);
    }

    private static void validateFolder(String folder) {
        if (folder != null && (folder.isBlank() || folder.equals(".") || folder.equals("..")
            || folder.contains("/") || folder.contains("\\") || folder.contains(":"))) throw new IllegalArgumentException("Invalid world folder");
    }

}
