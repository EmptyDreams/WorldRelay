package top.kmar.mc.worldreplay.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kmar.mc.worldreplay.client.SharedWorldLoginPolicy;
import top.kmar.mc.worldreplay.client.SharedWorldCreation;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** 受管世界应用固定登录模式，LAN 仅供隧道从本机访问；普通单人世界不受影响。 */
@Mixin(IntegratedServer.class)
public abstract class SharedPublishMixin {

    @Inject(method = "initServer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/server/IntegratedServer;initializeKeyPair()V"))
    private void worldrelay$loginPolicy(CallbackInfoReturnable<Boolean> callback) {
        var server = (MinecraftServer) (Object) this;
        if (SharedWorldCreation.allowsOfflinePlayers(server)) {
            server.setUsesAuthentication(false);
            var owner = server.getSingleplayerProfile();
            if (owner != null) server.setSingleplayerProfile(SharedWorldLoginPolicy.profile(owner, true));
        }
    }

    @ModifyArg(method = "publishServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"), index = 0)
    private InetAddress worldrelay$loopback(InetAddress address) {
        return SharedWorldCreation.managed((MinecraftServer) (Object) this) ? new InetSocketAddress("127.0.0.1", 0).getAddress() : address;
    }

}