package top.kmar.mc.worldreplay.mixin;

import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;

import java.io.File;

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

}
