package top.kmar.mc.worldreplay.client;

import net.minecraft.util.DirectoryLock;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/** 在后台验证原会话和工作目录；存档内容由原版加载，不复制或重新下载世界。 */
final class SharedWorldRecovery {

    private SharedWorldRecovery() {
    }

    record Prepared(Path directory, int heartbeatIntervalSeconds) {

    }

    static Prepared prepare(
        ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store
    ) throws IOException {
        if (!session.canResume()) throw new IOException("Session cannot resume a local world");
        Path world = store.saves(session.epoch()).resolve(session.folder());
        checkDirectory(world);
        var view = WorldOwnershipClient.command(settings, user, session, "ready", "RESUME");
        if (!view.phase().equals("STARTING")) throw new IOException("Unexpected recovery state");
        return new Prepared(world, view.heartbeatIntervalSeconds());
    }

    static boolean ownershipLost(Throwable failure) {
        return failure instanceof WorldOwnershipClient.RequestException rejected
            && (rejected.status() == 403 || rejected.status() == 409)
            && Set.of("STALE_EPOCH", "OWNER_MISMATCH", "SESSION_MISMATCH", "WORLD_NOT_OWNED",
            "ACQUISITION_CANCELLED", "ACQUISITION_COMPLETED").contains(rejected.code());
    }

    static void checkDirectory(Path world) throws IOException {
        if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS) || !world.toRealPath().equals(world)
            || !Files.isRegularFile(world.resolve(SharedPlayerData.MARKER_FILE), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed local world is unavailable");
        }
        // level.dat_old 仍由原版恢复界面处理，不在这里解析 NBT 或扫描区块。
        if (!Files.isRegularFile(world.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(world.resolve("level.dat_old"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local world has no saved level data");
        }
        Path lock = world.resolve("session.lock");
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local world lock is not a regular file");
        }
        try {
            if (DirectoryLock.isLocked(world)) throw new IOException("Local world is still in use");
        } catch (OverlappingFileLockException failure) {
            throw new IOException("Local world is still in use", failure);
        }
        // 原版加载器稍后会真正持有目录锁；检查后若有其他进程抢先打开，由原版拒绝双开。
    }

}
