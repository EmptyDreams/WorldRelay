package top.kmar.mc.worldreplay.compat.xaero;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/** 配置由 NeoForge 的 requiredMods 条件加载；目标接口不匹配时整组停用。 */
public final class XaeroMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!Boolean.parseBoolean(System.getProperty("worldrelay.xaero.enabled", "true"))) return false;
        try {
            ClassNode target = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
            if (compatible(target)) return true;
        } catch (Exception failure) { /* 可选模组升级或类布局变化时保留其原生行为。 */ }
        LoggerFactory.getLogger("WorldRelay/Xaero").warn("Xaero shared-world map compatibility disabled: unsupported interface in {}", targetClassName);
        return false;
    }

    static boolean compatible(ClassNode target) {
        String[] methods;
        if (target.name.equals("xaero/map/MapProcessor")) {
            methods = new String[]{"onInit(Lnet/minecraft/client/multiplayer/ClientPacketListener;)V",
                "getMainId(ILnet/minecraft/client/multiplayer/ClientPacketListener;)Ljava/lang/String;",
                "getAutoIdBase(Lnet/minecraft/client/multiplayer/ClientLevel;)Ljava/lang/Object;", "hasServerLevelId()Z"};
        } else if (target.name.equals("xaero/hud/minimap/world/state/MinimapWorldStateUpdater")) {
            methods = new String[]{"init()V", "getAutoRootContainerPath(I)Lxaero/hud/path/XaeroPath;",
                "getPotentialWorldNode(Lnet/minecraft/resources/ResourceKey;Z)Ljava/lang/String;"};
            if (target.fields.stream().noneMatch(field -> field.name.equals("connection") && field.desc.equals("Lnet/minecraft/client/multiplayer/ClientPacketListener;")))
                return false;
        } else return false;
        for (String signature : methods) {
            if (target.methods.stream().noneMatch(method -> (method.name + method.desc).equals(signature))) return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

}