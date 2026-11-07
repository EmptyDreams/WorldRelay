package top.kmar.mc.worldreplay.mixin.compat;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kmar.mc.worldreplay.client.SharedWorldConnection;

import java.nio.file.Path;
import java.util.Optional;

/** 收藏、查询历史和旧格式书签共同使用稳定的共享世界目录。 */
@Pseudo
@Mixin(targets = "mezz.jei.common.util.ServerConfigPathUtil", remap = false)
public abstract class JeiWorldPathMixin {

    @Inject(method = "getWorldPath(Ljava/nio/file/Path;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private static void worldrelay$sharedPath(Path base, CallbackInfoReturnable<Optional<Path>> callback) {
        String world = SharedWorldConnection.worldId(Minecraft.getInstance().getConnection());
        if (world != null) callback.setReturnValue(Optional.of(base.resolve("world/server").resolve(world + ".localhost")));
    }

}
