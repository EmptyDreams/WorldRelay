package top.kmar.mc.worldreplay.test;

import cpw.mods.modlauncher.Launcher;
import net.neoforged.fml.common.asm.RuntimeDistCleaner;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** 测试启动仍使用 SERVER 侧；在准备客户端目标前执行 NeoForge JUnit 自身的分发侧检查关闭步骤。 */
public final class ClientConnectionMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // JUnitMain 也执行此操作，但时机晚于 Mixin 目标解析；此插件仅存在于测试资源中。
        var cleaner = (RuntimeDistCleaner) Launcher.INSTANCE.environment().findLaunchPlugin("runtimedistcleaner").orElseThrow();
        cleaner.setDistribution(null);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
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
