package top.kmar.mc.worldreplay.playerdata;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import top.kmar.mc.worldreplay.playerdata.MissingPlayerDataConfirmation.Decision;
import top.kmar.mc.worldreplay.playerdata.MissingPlayerDataConfirmation.Request;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** 严格读取共享世界的玩家主文件，不修改存档内容。 */
public final class SharedPlayerData {

    public static final String MARKER_FILE = "worldrelay.shared";

    // 许可仅保存在当前进程中，重试期间重启内置服务器仍可保留玩家的确认结果。
    // 使用世界真实路径和 UUID 共同标识许可，防止跨世界或跨玩家使用。
    private static final Set<Request> CREATION_APPROVALS = ConcurrentHashMap.newKeySet();

    private final Path worldDirectory;
    private final boolean sharedWorld;

    public SharedPlayerData(Path worldDirectory) {
        try {
            this.worldDirectory = worldDirectory.toRealPath();
            this.sharedWorld = readSharedMarker(this.worldDirectory);
        } catch (IOException exception) {
            // 标记无法读取时必须报错，不能静默恢复为单人世界的加载方式。
            throw new UncheckedIOException("Cannot inspect WorldRelay world " + worldDirectory, exception);
        }
    }

    public boolean isSharedWorld() {
        return sharedWorld;
    }

    /** 检查时不消耗创建许可，也不创建、修复或备份任何文件。 */
    public CheckResult check(UUID playerId) {
        Request request = request(playerId);
        if (!sharedWorld) {
            return new CheckResult(Status.ERROR, request, "World is not marked as shared", null);
        }
        try {
            readExisting(playerId);
            return new CheckResult(Status.READY, request, "", null);
        } catch (NoSuchFileException exception) {
            return missing(request, exception);
        } catch (IOException | RuntimeException exception) {
            return unreadable(request, exception);
        }
    }

    public boolean hasCreationApproval(UUID playerId) {
        return sharedWorld && CREATION_APPROVALS.contains(request(playerId));
    }

    /** 保留未接入确认界面时的安全入口，返回 UNAVAILABLE，不会隐式授予创建玩家数据的许可。 */
    @SuppressWarnings("unused")
    public CompletionStage<Decision> requestCreation(UUID playerId) {
        return requestCreation(playerId, MissingPlayerDataConfirmation.UNAVAILABLE);
    }

    /**
     * 检查结果为 NEEDS_CREATION_CONFIRMATION 时，由后续接入的可信界面调用。
     * 远端接入必须将请求绑定到已认证玩家，不能接受客户端任意指定的 UUID。
     * 确认完成后允许重试加入，不恢复当前这次登录。
     */
    public CompletionStage<Decision> requestCreation(UUID playerId, MissingPlayerDataConfirmation confirmation) {
        Objects.requireNonNull(confirmation);
        CheckResult initial = check(playerId);
        if (initial.status() != Status.NEEDS_CREATION_CONFIRMATION) {
            return CompletableFuture.failedFuture(new LoadException(initial));
        }

        Request request = initial.request();
        return confirmation.confirm(request).thenApplyAsync(decision -> {
            Objects.requireNonNull(decision, "Confirmation must return a decision");
            if (decision == Decision.CREATE) {
                CheckResult latest = check(playerId);
                if (latest.status() == Status.ERROR) {
                    CREATION_APPROVALS.remove(request);
                    throw new LoadException(latest);
                }
                if (latest.status() == Status.NEEDS_CREATION_CONFIRMATION) {
                    CREATION_APPROVALS.add(request);
                } else {
                    // 文件已出现时应加载已有数据，不再保留初始化缺失数据的许可。
                    CREATION_APPROVALS.remove(request);
                }
            } else {
                CREATION_APPROVALS.remove(request);
            }
            return decision;
        });
    }

    /**
     * 在原版实际加载玩家数据时调用。返回空值表示玩家已明确授予一次初始化许可，
     * 允许原版创建新玩家数据；读取失败必须抛出异常。
     */
    public Optional<CompoundTag> readPrimary(UUID playerId) {
        if (!sharedWorld) {
            throw new IllegalStateException("Strict loading is only available for shared worlds");
        }
        Request request = request(playerId);
        boolean approved = CREATION_APPROVALS.remove(request);
        try {
            return Optional.of(readExisting(playerId));
        } catch (NoSuchFileException exception) {
            if (approved) {
                return Optional.empty();
            }
            throw new LoadException(missing(request, exception));
        } catch (IOException | RuntimeException exception) {
            throw new LoadException(unreadable(request, exception));
        }
    }

    private Request request(UUID playerId) {
        return new Request(worldDirectory, Objects.requireNonNull(playerId));
    }

    private CompoundTag readExisting(UUID playerId) throws IOException {
        Path file = worldDirectory.resolve("playerdata").resolve(playerId + ".dat");
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Player data is not a regular file: " + file);
        }
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
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

    private static CheckResult missing(Request request, Exception cause) {
        return new CheckResult(Status.NEEDS_CREATION_CONFIRMATION, request, "Primary player data file is missing", cause);
    }

    private static CheckResult unreadable(Request request, Exception cause) {
        return new CheckResult(Status.ERROR, request, "Cannot read primary player data: " + cause.getMessage(), cause);
    }

    public enum Status {
        READY,
        NEEDS_CREATION_CONFIRMATION,
        ERROR
    }

    public record CheckResult(Status status, Request request, String detail, Throwable cause) {

    }

    public static final class LoadException extends RuntimeException {

        private final CheckResult result;

        public LoadException(CheckResult result) {
            super(result.detail() + " (world=" + result.request().worldDirectory()
                + ", player=" + result.request().playerId() + ")", result.cause());
            this.result = result;
        }

        public CheckResult result() {
            return result;
        }

    }

    /** 由 PlayerDataStorage 的 Mixin 实现，客户端与独立服务端均可使用。 */
    public interface Access {

        SharedPlayerData worldrelay$getSharedPlayerData();

    }

}
