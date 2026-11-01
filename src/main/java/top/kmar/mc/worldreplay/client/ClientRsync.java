package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.mc.worldreplay.sync.BundledRsync;
import top.kmar.mc.worldreplay.sync.RsyncTools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 客户端启动时后台准备工具；同步层可等待 prepare()，提取失败后可再次调用重试。 */
@EventBusSubscriber(modid = WorldRelay.MODID, value = Dist.CLIENT)
public final class ClientRsync {

    private ClientRsync() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        prepare().whenComplete((tools, error) -> {
            if (error instanceof CompletionException && error.getCause() instanceof UnsupportedOperationException) {
                WorldRelay.LOGGER.info("No bundled rsync for this platform; a custom path can be configured in settings");
            } else if (error != null) {
                WorldRelay.LOGGER.error("Failed to prepare rsync; check the configured path or retry", error);
            } else {
                WorldRelay.LOGGER.info("WorldRelay rsync prepared at {}", tools.rsync());
            }
        });
    }

    /** 不阻塞调用线程；调用方应组合异步流程，不要在渲染或服务器线程 join/get。 */
    public static CompletableFuture<BundledRsync.Executables> prepare() {
        Path gameDirectory = FMLPaths.GAMEDIR.get();
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 每次读取原子保存后的配置，避免继续使用修改设置之前缓存的程序路径。
                var settings = ClientConnectionSettings.load(gameDirectory.resolve("config/worldrelay-client.json"));
                return RsyncTools.prepare(gameDirectory, settings.rsyncMode(), settings.rsyncPath(), settings.sshPath());
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.ioPool());
    }

}
