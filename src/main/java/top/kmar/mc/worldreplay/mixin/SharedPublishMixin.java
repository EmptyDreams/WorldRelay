package top.kmar.mc.worldreplay.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import top.kmar.mc.worldreplay.client.SharedWorldCreation;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** 受管世界仅供隧道从本机访问；普通单人世界的开放 LAN 行为不变。 */
@Mixin(IntegratedServer.class)
public abstract class SharedPublishMixin {

    @ModifyArg(method = "publishServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"), index = 0)
    private InetAddress worldrelay$loopback(InetAddress address) {
        return SharedWorldCreation.managed((MinecraftServer) (Object) this) ? new InetSocketAddress("127.0.0.1", 0).getAddress() : address;
    }

}