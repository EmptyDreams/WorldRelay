package top.kmar.mc.worldreplay.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import top.kmar.mc.worldreplay.client.SharedWorldCreation;

/** IPN 将 Kotlin 的 serverIdentifier 内联到了各加载器，需覆盖每个实际取名点。 */
@Pseudo
@Mixin(targets = {
    "org.anti_ad.mc.ipnext.parser.ProfilesLoader",
    "org.anti_ad.mc.ipnext.parser.LockSlotsLoader",
    "org.anti_ad.mc.ipnext.parser.RefillSlotsLoader",
    "org.anti_ad.mc.ipnext.parser.VillagerBookmarksLoader",
    "org.anti_ad.mc.ipnext.specific.SmallHelpersKt"
}, remap = false)
public abstract class IpnWorldIdentityMixin {

    @ModifyExpressionValue(method = "*", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/storage/WorldData;getLevelName()Ljava/lang/String;"))
    @SuppressWarnings("UnresolvedMixinReference") // 默认类路径不含可选 IPN；发布类的实际调用点由兼容测试验证。
    private static String worldrelay$hostIdentifier(String original) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        String world = server == null ? null : SharedWorldCreation.worldId(server);
        // 访客已使用同一逻辑域名；按服务器配置关闭时，IPN 不会进入这个取名分支。
        return world == null ? original : world + ".localhost";
    }

}
