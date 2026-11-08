package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/** 失效会话的选择先落盘；复用本地存档也必须重新取得所有权，并在新任期目录下打开。 */
final class LocalWorldReplacement {

    private LocalWorldReplacement() {
    }

    static LocalWorldSession.LocalSource source(LocalWorldSession session, LocalWorldSessionStore store) throws IOException {
        if (session.epoch() > 0 && session.folder() != null) {
            Path current = store.saves(session.epoch()).resolve(session.folder());
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                SharedWorldRecovery.checkDirectory(current);
                return new LocalWorldSession.LocalSource(session.epoch(), session.folder(), session.baseVersion());
            }
        }
        var source = session.localSource();
        if (source == null) throw new IOException("No local world to reuse");
        SharedWorldRecovery.checkDirectory(store.saves(source.epoch()).resolve(source.folder()));
        return source;
    }

    static boolean allowed(ClientConnectionSettings settings, String user, LocalWorldSession previous, LocalWorldSessionStore store) throws IOException {
        var source = source(previous, store);
        JsonObject body = new JsonObject();
        body.addProperty("worldId", previous.worldId());
        body.addProperty("deviceId", previous.deviceId());
        body.addProperty("sourceEpoch", source.epoch());
        WorldOwnershipClient.addBase(body, source.baseVersion());
        try {
            var response = JsonParser.parseString(WorldOwnershipClient.post(settings, user, "check-local-reuse", body, false)).getAsJsonObject();
            if (!previous.worldId().equals(ClientJson.requiredString(response, "worldId")) || !response.getAsJsonPrimitive("allowed").isBoolean())
                throw new IOException("Invalid local reuse eligibility response");
            return response.get("allowed").getAsBoolean();
        } catch (WorldOwnershipClient.RequestException failure) {
            if (failure.status() == 404) return false; // 旧服务未提供证明时只允许服务器来源。
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid local reuse eligibility response", failure);
        }
    }

    static boolean reuseDenied(Throwable failure) {
        return failure instanceof WorldOwnershipClient.RequestException rejected
            && (rejected.code().equals("LOCAL_REUSE_NOT_ALLOWED") || rejected.status() == 404
            || rejected.status() == 400 && rejected.code().equals("INVALID_OWNERSHIP_REQUEST"));
    }

    static LocalWorldSession acceptOwnership(LocalWorldSession pending, WorldOwnershipClient.View view) {
        var owned = pending.owned(view);
        if (owned.localSource() == null || matches(owned.localSource(), view.epoch(), view.baseVersion())) return owned;
        // 旧客户端可能已经取得任期但尚未移动目录；沿用有效所有权，从新目录下载，不能留下无人可恢复的锁。
        return new LocalWorldSession(owned.worldId(), owned.deviceId(), owned.requestId(), owned.sessionId(), owned.epoch(),
            view.hasSave() ? LocalWorldSession.Stage.DOWNLOADING : LocalWorldSession.Stage.PREPARING,
            view.hasSave() ? "server-" + owned.sessionId() : null, owned.baseVersion(), null, null);
    }

    private static boolean matches(LocalWorldSession.LocalSource source, long epoch, Long baseVersion) {
        return source.epoch() <= Long.MAX_VALUE - 2 && epoch == source.epoch() + 2 && Objects.equals(source.baseVersion(), baseVersion);
    }

    static LocalWorldSession select(LocalWorldSessionStore store, LocalWorldSession previous, boolean useLocal) throws IOException {
        if (!previous.equals(store.read())) throw new IOException("Local recovery session changed");
        var source = useLocal ? source(previous, store) : null;
        var next = new LocalWorldSession(previous.worldId(), previous.deviceId(), UUID.randomUUID().toString(), null, 0,
            LocalWorldSession.Stage.ACQUIRING, null, null, null, null, source);
        store.write(next);
        return next;
    }

    static SharedWorldRecovery.Prepared prepare(
        ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store
    ) throws IOException {
        var source = session.localSource();
        if (source == null || !matches(source, session.epoch(), session.baseVersion()) || session.stage() != LocalWorldSession.Stage.PREPARING
            || !source.folder().equals(session.folder()))
            throw new IOException("Local reuse requires fresh ownership");
        if (!session.equals(store.read())) throw new IOException("Local reuse intent was not persisted");
        Path oldWorld = store.saves(source.epoch()).resolve(source.folder());
        Path target = store.saves(session.epoch()).resolve(source.folder());
        boolean moved = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (moved && Files.exists(oldWorld, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Local reuse target already exists");
        SharedWorldRecovery.checkDirectory(moved ? target : oldWorld);
        var view = WorldOwnershipClient.command(settings, user, session, "ready", "RESUME");
        if (!view.phase().equals("STARTING")) throw new IOException("Unexpected local reuse state");
        // 选择和新会话已经落盘；原子移动后崩溃，重试可从新任期目录继续，不额外复制存档。
        if (!moved) {
            Files.move(oldWorld, target, StandardCopyOption.ATOMIC_MOVE);
            SnapshotFiles.forceDirectory(oldWorld.getParent());
            SnapshotFiles.forceDirectory(target.getParent());
        }
        return new SharedWorldRecovery.Prepared(target, view.heartbeatIntervalSeconds());
    }

}