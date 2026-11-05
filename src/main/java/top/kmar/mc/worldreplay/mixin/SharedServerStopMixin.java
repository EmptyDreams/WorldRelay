package top.kmar.mc.worldreplay.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.kmar.mc.worldreplay.client.SharedWorldCreation;

/** 1.21.1 的 ServerStoppedEvent 在 stopServer 抛异常后仍会触发，必须额外确认保存方法正常返回。 */
@Mixin(MinecraftServer.class)
public abstract class SharedServerStopMixin {

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void worldrelay$saved(CallbackInfo callback) {
        SharedWorldCreation.saved((MinecraftServer) (Object) this);
    }

}