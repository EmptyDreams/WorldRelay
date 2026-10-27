package top.kmar.mc.worldreplay.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;

import java.io.File;
import java.util.Optional;
import java.util.function.Supplier;

@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin implements SharedPlayerData.Access {

    @Shadow
    @Final
    private File playerDir;

    @Unique
    private SharedPlayerData worldrelay$sharedPlayerData;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void worldrelay$initialize(CallbackInfo callback) {
        worldrelay$sharedPlayerData = new SharedPlayerData(playerDir.toPath().getParent());
    }

    @Override
    public SharedPlayerData worldrelay$getSharedPlayerData() {
        return worldrelay$sharedPlayerData;
    }

    /**
     * 在原版读取玩家文件前接管共享世界的读取，仅允许当前 UUID 的主文件，
     * 将缺失或读取失败交给严格读取服务处理，避免原版吞掉异常后继续初始化玩家。
     */
    @Inject(method = "load(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;)Ljava/util/Optional;",
        at = @At("HEAD"), cancellable = true)
    private void worldrelay$readPrimary(Player player, String suffix, CallbackInfoReturnable<Optional<CompoundTag>> callback) {
        if (worldrelay$sharedPlayerData.isSharedWorld()) {
            if (!".dat".equals(suffix)) {
                throw new IllegalStateException("Shared worlds must not load backup player data");
            }
            callback.setReturnValue(worldrelay$sharedPlayerData.readPrimary(player.getUUID()));
        }
    }

    /**
     * 拦截主文件结果上的 Optional.or 调用，共享世界直接返回主文件结果，
     * 禁止执行读取 .dat_old 的回退逻辑，包括已确认新建玩家时的空结果。
     */
    @Redirect(method = "load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;",
        at = @At(value = "INVOKE", target = "Ljava/util/Optional;or(Ljava/util/function/Supplier;)Ljava/util/Optional;"))
    private Optional<CompoundTag> worldrelay$skipBackup(
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        Optional<CompoundTag> primary,
        Supplier<? extends Optional<? extends CompoundTag>> fallback
    ) {
        // 保留后续原版的数据版本升级、Player.load 和 NeoForge 玩家加载事件。
        return worldrelay$sharedPlayerData.isSharedWorld() ? primary : primary.or(fallback);
    }

    /**
     * 在原版创建损坏文件备份前取消共享世界的备份操作。严格读取失败时已直接报错，
     * 已确认新玩家的空结果也不表示文件损坏，避免该流程额外写入文件副本。
     */
    @Inject(method = "backup", at = @At("HEAD"), cancellable = true)
    private void worldrelay$skipAutomaticBackup(Player player, String suffix, CallbackInfo callback) {
        if (worldrelay$sharedPlayerData.isSharedWorld()) {
            callback.cancel();
        }
    }

}