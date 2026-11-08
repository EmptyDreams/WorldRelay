package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.time.Instant;
import java.util.Objects;

/** 管理端显式放弃未提交进度的预览和审计结果；不读取或删除玩家的本地世界。 */
public final class WorldAbandonment {

    private WorldAbandonment() {
    }

    static void validate(long epoch, Long version, WorldPhase phase, String committedAt, String reason) {
        Objects.requireNonNull(phase);
        if (epoch < 1 || epoch == Long.MAX_VALUE || phase == WorldPhase.FREE
            || (version != null && version < 1) || (version == null) != (committedAt == null)
            || reason == null || reason.codePointCount(0, reason.length()) > 256 || reason.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid abandonment request");
        if (committedAt != null) {
            //noinspection ResultOfMethodCallIgnored
            Instant.parse(committedAt);
        }
    }

    public record Request(String worldId, String abandonId, long expectedEpoch, Long expectedVersion, WorldPhase expectedPhase,
                          String ownerUsername, String lastCommittedAt, String reason) {

        public Request {
            WorldStore.validateId(worldId);
            WorldStore.validateId(abandonId);
            UserStore.validateUsername(ownerUsername);
            validate(expectedEpoch, expectedVersion, expectedPhase, lastCommittedAt, reason);
        }

        public String toJson(boolean confirm) {
            var writer = new JsonWriter().beginObject().name("worldId").value(worldId).name("abandonId").value(abandonId)
                .name("expectedEpoch").value(expectedEpoch).name("expectedVersion");
            if (expectedVersion == null) writer.nullValue();
            else writer.value(expectedVersion);
            writer.name("expectedPhase").value(expectedPhase.name()).name("ownerUsername").value(ownerUsername).name("lastCommittedAt");
            if (lastCommittedAt == null) writer.nullValue();
            else writer.value(lastCommittedAt);
            return writer.name("reason").value(reason).name("confirm").value(confirm).endObject().finish();
        }

    }

    public record Result(String worldId, String abandonId, String previousOwner, long revokedEpoch, long epoch,
                         Long committedVersion, String lastCommittedAt, String abandonedAt) {

        public String toJson() {
            var writer = new JsonWriter().beginObject().name("worldId").value(worldId).name("abandonId").value(abandonId)
                .name("previousOwner").value(previousOwner).name("revokedEpoch").value(revokedEpoch).name("epoch").value(epoch)
                .name("committedVersion");
            if (committedVersion == null) writer.nullValue();
            else writer.value(committedVersion);
            writer.name("lastCommittedAt");
            if (lastCommittedAt == null) writer.nullValue();
            else writer.value(lastCommittedAt);
            return writer.name("abandonedAt").value(abandonedAt).endObject().finish();
        }

    }

}