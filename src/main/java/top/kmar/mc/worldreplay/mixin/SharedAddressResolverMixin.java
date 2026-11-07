package top.kmar.mc.worldreplay.mixin;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kmar.mc.worldreplay.client.SharedWorldConnection;

import java.util.Optional;

/** 仅处理 WorldRelay UUID.localhost；未登记的受管地址失败，不交给 DNS 或 SRV。 */
@Mixin(ServerNameResolver.class)
public abstract class SharedAddressResolverMixin {

    @Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
    private void worldrelay$resolve(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> callback) {
        if (SharedWorldConnection.managedHost(address.getHost())) callback.setReturnValue(SharedWorldConnection.resolve(address));
    }

}