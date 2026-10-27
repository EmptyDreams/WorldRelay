package top.kmar.mc.worldreplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import top.kmar.mc.worldreplay.WorldRelay;

/** 仅在客户端为主菜单添加共享世界入口。 */
@EventBusSubscriber(modid = WorldRelay.MODID, value = Dist.CLIENT)
public final class TitleScreenEvents {

    private TitleScreenEvents() {
    }

    /** 在主菜单初始化完成后，将多人游戏与共享世界并排放在同一行。 */
    @SubscribeEvent
    public static void addSharedWorldButton(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) {
            return;
        }

        Button multiplayer = event.getListenersList().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("menu.multiplayer"))
            .findFirst()
            .orElse(null);
        if (multiplayer == null) {
            return;
        }

        // 平分原多人游戏按钮的宽度并留出 4 像素间隙，保留原按钮的点击逻辑和可用状态。
        int gap = 4;
        int halfWidth = (multiplayer.getWidth() - gap) / 2;
        multiplayer.setWidth(halfWidth);
        event.addListener(Button.builder(Component.translatable("worldrelay.menu.shared_worlds"),
                button -> Minecraft.getInstance().setScreen(new SharedWorldScreen(screen)))
            .bounds(multiplayer.getX() + halfWidth + gap, multiplayer.getY(), halfWidth, multiplayer.getHeight())
            .build());
    }

}