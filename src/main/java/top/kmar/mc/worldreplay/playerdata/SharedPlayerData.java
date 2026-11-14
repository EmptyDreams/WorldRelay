package top.kmar.mc.worldreplay.playerdata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** 识别共享世界，使房主也按 UUID 加载；玩家文件的读取和恢复完全沿用原版。 */
public final class SharedPlayerData {

    public static final String MARKER_FILE = "worldrelay.shared";

    private final boolean sharedWorld;

    public SharedPlayerData(Path worldDirectory) {
        try {
            this.sharedWorld = readSharedMarker(worldDirectory.toRealPath());
        } catch (IOException exception) {
            // 标记无法读取时必须报错，不能静默恢复为单人世界的加载方式。
            throw new UncheckedIOException("Cannot inspect WorldRelay world " + worldDirectory, exception);
        }
    }

    public boolean isSharedWorld() {
        return sharedWorld;
    }

    private static boolean readSharedMarker(Path worldDirectory) throws IOException {
        Path marker = worldDirectory.resolve(MARKER_FILE);
        try {
            if (!Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("Shared-world marker is not a regular file: " + marker);
            }
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    /** 由 PlayerDataStorage 的 Mixin 实现，客户端与独立服务端均可使用。 */
    public interface Access {

        SharedPlayerData worldrelay$getSharedPlayerData();

    }

}
