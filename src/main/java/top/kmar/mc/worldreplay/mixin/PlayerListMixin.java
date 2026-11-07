package top.kmar.mc.worldreplay.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;
import top.kmar.mc.worldreplay.playerdata.TunnelPlayerAccess;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow
    @Final
    private PlayerDataStorage playerIo;

    @Unique
    private SharedPlayerData worldrelay$playerData() {
        return ((SharedPlayerData.Access) playerIo).worldrelay$getSharedPlayerData();
    }

    /**
     * 拦截加载玩家时获取 level.dat 单人玩家数据的调用，对共享世界返回空值，
     * 让房主也进入 UUID 文件加载分支，防止交接后读到上一任房主的数据。
     */
    @Redirect(method = "load", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/storage/WorldData;getLoadedPlayerTag()Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag worldrelay$ignoreSingleplayerData(WorldData worldData) {
        return worldrelay$playerData().isSharedWorld() ? null : worldData.getLoadedPlayerTag();
    }

    /**
     * 在原版登录检查通过后、玩家进入世界前检查共享玩家数据；缺失且未获创建许可，
     * 或文件无法读取时拒绝加入，避免初始化空白数据并在后续保存时覆盖原进度。
     */
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void worldrelay$checkPlayerData(
        SocketAddress address, GameProfile profile,
        CallbackInfoReturnable<Component> callback
    ) {
        if (callback.getReturnValue() != null) {
            return;
        }
        SharedPlayerData playerData = worldrelay$playerData();
        if (!playerData.isSharedWorld()) {
            return;
        }
        if (!TunnelPlayerAccess.matches(address, profile)) {
            callback.setReturnValue(Component.translatable("worldrelay.disconnect.tunnel_identity_mismatch"));
            return;
        }

        SharedPlayerData.CheckResult result = playerData.check(profile.getId());
        if (result.status() == SharedPlayerData.Status.NEEDS_CREATION_CONFIRMATION) {
            if (!playerData.hasCreationApproval(profile.getId()) && !playerData.approveTunnelCreation(address, profile)) {
                callback.setReturnValue(Component.translatable("worldrelay.disconnect.player_data_confirmation_required"));
            }
        } else if (result.status() == SharedPlayerData.Status.ERROR) {
            WorldRelay.LOGGER.error("Cannot admit player {} to shared world {}: {}", profile.getId(),
                result.request().worldDirectory(), result.detail(), result.cause());
            callback.setReturnValue(Component.translatable("worldrelay.disconnect.player_data_unreadable"));
        }
    }

}
