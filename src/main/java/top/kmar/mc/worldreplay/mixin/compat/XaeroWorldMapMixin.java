package top.kmar.mc.worldreplay.mixin.compat;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kmar.mc.worldreplay.compat.xaero.XaeroMapIdentity;

/** 共享世界使用多人地图及固定层级 ID；MC 的内置服务器和连接方式保持真实。 */
@Pseudo
@Mixin(targets = "xaero.map.MapProcessor", remap = false)
public abstract class XaeroWorldMapMixin {

    @Unique
    private XaeroMapIdentity worldrelay$mapIdentity;

    @Inject(method = "onInit(Lnet/minecraft/client/multiplayer/ClientPacketListener;)V", at = @At("HEAD"))
    private void worldrelay$bindIdentity(ClientPacketListener connection, CallbackInfo callback) {
        worldrelay$mapIdentity = XaeroMapIdentity.capture(connection);
    }

    @Inject(method = "getMainId(ILnet/minecraft/client/multiplayer/ClientPacketListener;)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void worldrelay$root(CallbackInfoReturnable<String> callback) {
        if (worldrelay$mapIdentity != null) callback.setReturnValue(worldrelay$mapIdentity.root());
    }

    @Inject(method = "getAutoIdBase(Lnet/minecraft/client/multiplayer/ClientLevel;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true)
    private void worldrelay$levelId(CallbackInfoReturnable<Object> callback) {
        if (worldrelay$mapIdentity != null) callback.setReturnValue(XaeroMapIdentity.LEVEL_ID);
    }

    @Inject(method = "hasServerLevelId()Z", at = @At("HEAD"), cancellable = true)
    private void worldrelay$serverSelection(CallbackInfoReturnable<Boolean> callback) {
        // 让 Xaero 同时按 Integer 保存已使用的层级 ID，并启用可写的自动服务器地图。
        if (worldrelay$mapIdentity != null) callback.setReturnValue(true);
    }

}