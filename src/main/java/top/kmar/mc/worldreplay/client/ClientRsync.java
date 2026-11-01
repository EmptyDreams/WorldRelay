package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.mc.worldreplay.sync.BundledRsync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 客户端启动时后台准备工具；同步层可等待 prepare()，提取失败后可再次调用重试。 */
@EventBusSubscriber(modid = WorldRelay.MODID, value = Dist.CLIENT)
public final class ClientRsync {

    private static CompletableFuture<BundledRsync.Executables> preparation;

    private ClientRsync() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (BundledRsync.isNotSupportedPlatform()) {
            WorldRelay.LOGGER.info("Skipping bundled rsync extraction: only Windows x64 is supported");
            return;
        }
        prepare().whenComplete((tools, error) -> {
            if (error != null) {
                WorldRelay.LOGGER.error("Failed to extract bundled rsync; preparation can be retried", error);
            } else {
                WorldRelay.LOGGER.info("Bundled rsync extracted to {}", tools.rsync().getParent());
            }
        });
    }

    /** 不阻塞调用线程；调用方应组合异步流程，不要在渲染或服务器线程 join/get。 */
    public static synchronized CompletableFuture<BundledRsync.Executables> prepare() {
        if (BundledRsync.isNotSupportedPlatform()) {
            return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Bundled rsync is only available for Windows x64"));
        }
        if (preparation == null || preparation.isCompletedExceptionally()) {
            Path gameDirectory = FMLPaths.GAMEDIR.get();
            preparation = CompletableFuture.supplyAsync(() -> {
                try {
                    return BundledRsync.extract(gameDirectory);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, Util.ioPool());
        }
        // 避免调用方取消或手动完成所有同步操作共用的 future。
        return preparation.copy();
    }

}