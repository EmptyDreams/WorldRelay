package top.kmar.mc.worldreplay.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpList;
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
import top.kmar.mc.worldreplay.permissions.MemoryOpList;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;
import top.kmar.mc.worldreplay.playerdata.TunnelPlayerAccess;

import java.io.File;
import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow
    @Final
    private PlayerDataStorage playerIo;

    @Shadow
    @Final
    private ServerOpList ops;

    /** 在列表构造时选择内存实现，不加载、保存或替换本机 ops.json。 */
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/server/players/ServerOpList"))
    private ServerOpList worldrelay$memoryOps(
        File file, MinecraftServer server, LayeredRegistryAccess<RegistryLayer> registries,
        PlayerDataStorage storage, int maxPlayers
    ) {
        boolean shared = ((SharedPlayerData.Access) storage).worldrelay$getSharedPlayerData().isSharedWorld();
        return shared ? new MemoryOpList(file) : new ServerOpList(file);
    }

    /** 共享世界只认内存名单；房主身份、世界作弊和 LAN 全员开关均不额外授予 OP。 */
    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
    private void worldrelay$listOnlyPermissions(GameProfile profile, CallbackInfoReturnable<Boolean> callback) {
        if (ops instanceof MemoryOpList) {
            callback.setReturnValue(ops.get(profile) != null);
        }
    }

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

    /** 玩家数据由原版加载；只校验实际登录身份与已鉴权隧道一致。 */
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void worldrelay$checkTunnelIdentity(
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
        }
    }

}
