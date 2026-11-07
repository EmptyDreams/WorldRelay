package top.kmar.mc.worldreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import top.kmar.mc.worldreplay.client.SharedWorldConnection;

/** 1.21.1 原版从解析结果取端口；受管连接握手应继续使用固定逻辑端口。 */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class SharedConnectMixin {

    @ModifyArgs(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;initiateServerboundPlayConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Z)V"))
    private void worldrelay$logicalPort(Args arguments) {
        if (SharedWorldConnection.managedHost(arguments.get(0))) arguments.set(1, 25565);
    }

}