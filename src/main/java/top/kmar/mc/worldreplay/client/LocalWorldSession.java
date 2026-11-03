package top.kmar.mc.worldreplay.client;

import java.util.UUID;

/** 会话是恢复记录，不包含 Token。START_REQUESTED 起不允许重新生成或取消创建。 */
record LocalWorldSession(String worldId, String deviceId, String requestId, String sessionId, long epoch,
                         Stage stage, String folder) {
    enum Stage {ACQUIRING, PREPARING, CANCELLING, CANCELLED, START_REQUESTED, STARTED, STOPPING}

    LocalWorldSession {
        for (String id : new String[]{worldId, deviceId, requestId}) {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid session UUID");
        }
        if (stage == null || epoch < 0 || (sessionId == null) != (epoch == 0)) throw new IllegalArgumentException("Invalid session");
        if (sessionId != null && !UUID.fromString(sessionId).toString().equals(sessionId)) throw new IllegalArgumentException("Invalid session UUID");
        if (stage != Stage.ACQUIRING && sessionId == null) throw new IllegalArgumentException("Missing ownership");
        if (folder != null && (folder.isBlank() || folder.equals(".") || folder.equals("..")
            || folder.contains("/") || folder.contains("\\") || folder.contains(":"))) throw new IllegalArgumentException("Invalid world folder");
        if ((stage == Stage.START_REQUESTED || stage == Stage.STARTED || stage == Stage.STOPPING) && folder == null) {
            throw new IllegalArgumentException("Missing world folder");
        }
    }

    LocalWorldSession owned(WorldOwnershipClient.View view) {
        return new LocalWorldSession(worldId, deviceId, requestId, view.sessionId(), view.epoch(), Stage.PREPARING, null);
    }

    LocalWorldSession with(Stage next, String target) {
        return new LocalWorldSession(worldId, deviceId, requestId, sessionId, epoch, next, target);
    }

    boolean canOpenCreation() { return stage == Stage.PREPARING; }
    boolean canCancel() { return stage == Stage.PREPARING || stage == Stage.CANCELLING; }
}
