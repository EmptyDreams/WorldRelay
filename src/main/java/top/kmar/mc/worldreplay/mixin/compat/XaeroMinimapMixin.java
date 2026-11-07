package top.kmar.mc.worldreplay.mixin.compat;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kmar.mc.worldreplay.compat.xaero.XaeroMapIdentity;
import xaero.hud.path.XaeroPath;

/** 路标与世界地图共用稳定根目录和子地图，单独安装 Minimap 时也生效。 */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.world.state.MinimapWorldStateUpdater", remap = false)
public abstract class XaeroMinimapMixin {

    @Shadow
    @Final
    private ClientPacketListener connection;
    @Unique
    private XaeroMapIdentity worldrelay$mapIdentity;

    @Inject(method = "init()V", at = @At("HEAD"))
    private void worldrelay$bindIdentity(CallbackInfo callback) {
        worldrelay$mapIdentity = XaeroMapIdentity.capture(connection);
    }

    @Inject(method = "getAutoRootContainerPath(I)Lxaero/hud/path/XaeroPath;", at = @At("HEAD"), cancellable = true)
    private void worldrelay$root(CallbackInfoReturnable<XaeroPath> callback) {
        if (worldrelay$mapIdentity != null) callback.setReturnValue(XaeroPath.root(worldrelay$mapIdentity.root()));
    }

    @Inject(method = "getPotentialWorldNode(Lnet/minecraft/resources/ResourceKey;Z)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void worldrelay$subWorld(CallbackInfoReturnable<String> callback) {
        if (worldrelay$mapIdentity != null) callback.setReturnValue(XaeroMapIdentity.SUB_WORLD);
    }

}