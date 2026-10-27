package top.kmar.mc.worldreplay;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

// 此处的模组 ID 应与 META-INF/neoforge.mods.toml 中的声明一致。
@Mod(WorldRelay.MODID)
public class WorldRelay {

    // 统一定义模组 ID，供其他代码引用。
    public static final String MODID = "worldrelay";
    // 模组共用的 SLF4J 日志记录器。
    public static final Logger LOGGER = LogUtils.getLogger();

    // 模组加载时首先执行此构造方法。
    // FML 会识别 IEventBus、ModContainer 等参数类型并自动传入对应实例。
    public WorldRelay(IEventBus modEventBus, ModContainer modContainer) {
    }

}