package top.kmar.mc.worldreplay.mixin;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.kmar.mc.worldreplay.client.SharedWorldCreation;

/** 仅绑定 WorldRelay 打开的原版实例；普通单人创建不改变行为。签名按 1.21.1 核对。 */
// 原版目标方法固定使用已弃用的 SpecialWorldProperty，Mixin 必须保持相同参数签名。
@SuppressWarnings("deprecation")
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Unique private SharedWorldCreation.Creation worldrelay$creation;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void worldrelay$bind(CallbackInfo callback) {
        worldrelay$creation = SharedWorldCreation.opening();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;getLevelSource()Lnet/minecraft/world/level/storage/LevelStorageSource;"))
    private LevelStorageSource worldrelay$folderSource(Minecraft client) {
        var creation = SharedWorldCreation.opening();
        return creation == null ? client.getLevelSource() : creation.storage();
    }

    @Redirect(method = "createNewWorldDirectory", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;getLevelSource()Lnet/minecraft/world/level/storage/LevelStorageSource;"))
    private LevelStorageSource worldrelay$worldSource(Minecraft client) {
        return worldrelay$creation == null ? client.getLevelSource() : worldrelay$creation.storage();
    }

    @Invoker("createNewWorld")
    protected abstract void worldrelay$create(PrimaryLevelData.SpecialWorldProperty property,
                                            LayeredRegistryAccess<RegistryLayer> registries, Lifecycle lifecycle);

    @Inject(method = "createNewWorld", at = @At("HEAD"), cancellable = true)
    private void worldrelay$beforeGeneration(PrimaryLevelData.SpecialWorldProperty property,
        LayeredRegistryAccess<RegistryLayer> registries, Lifecycle lifecycle, CallbackInfo callback) {
        if (worldrelay$creation == null || worldrelay$creation.resuming()) return;
        callback.cancel();
        String folder = ((CreateWorldScreen) (Object) this).getUiState().getTargetFolder();
        worldrelay$creation.begin(folder, () -> worldrelay$create(property, registries, lifecycle));
    }
}
