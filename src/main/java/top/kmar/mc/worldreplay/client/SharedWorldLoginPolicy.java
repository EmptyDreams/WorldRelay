package top.kmar.mc.worldreplay.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/** 登录模式在世界创建后固定；离线世界的房主与访客均采用原版离线身份。 */
public final class SharedWorldLoginPolicy {
    private SharedWorldLoginPolicy() { }

    public static GameProfile profile(GameProfile original, boolean allowOfflinePlayers) {
        return allowOfflinePlayers ? UUIDUtil.createOfflineProfile(original.getName()) : original;
    }

    static UUID playerId(String name, UUID launcherId, boolean allowOfflinePlayers) {
        return allowOfflinePlayers ? UUIDUtil.createOfflinePlayerUUID(name) : launcherId;
    }
}
