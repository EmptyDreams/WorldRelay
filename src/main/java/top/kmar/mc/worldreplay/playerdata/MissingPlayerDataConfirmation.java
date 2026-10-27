package top.kmar.mc.worldreplay.playerdata;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 新玩家确认界面的接入接口。实现方必须先征得玩家同意，才能返回 CREATE。
 * 不得阻塞 Minecraft 渲染线程或服务器线程来等待异步结果。
 */
@FunctionalInterface
public interface MissingPlayerDataConfirmation {

    MissingPlayerDataConfirmation UNAVAILABLE = request -> CompletableFuture.completedFuture(Decision.UNAVAILABLE);

    CompletionStage<Decision> confirm(Request request);

    enum Decision {
        CREATE,
        CANCEL,
        UNAVAILABLE
    }

    /** 标识本地世界和已认证玩家的 UUID，用于初始化世界内的玩家数据。 */
    record Request(Path worldDirectory, UUID playerId) {

    }

}