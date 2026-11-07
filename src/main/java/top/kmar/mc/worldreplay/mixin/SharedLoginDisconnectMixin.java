package top.kmar.mc.worldreplay.mixin;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.kmar.mc.worldreplay.client.SharedWorldConnection;

/** 原版先显示登录失败，再按受管连接的准确拒绝原因显示新玩家确认。 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class SharedLoginDisconnectMixin {

    @Shadow
    @Final
    private ServerData serverData;

    @Inject(method = "onDisconnect", at = @At("RETURN"))
    private void worldrelay$confirm(DisconnectionDetails details, CallbackInfo callback) {
        if (serverData != null) SharedWorldConnection.disconnected(serverData.ip, details.reason());
    }

}