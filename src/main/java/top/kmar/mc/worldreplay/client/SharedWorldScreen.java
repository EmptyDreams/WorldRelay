package top.kmar.mc.worldreplay.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 共享世界的空白页面，后续在此接入世界列表等内容。 */
public final class SharedWorldScreen extends Screen {

    private final Screen parent;

    public SharedWorldScreen(Screen parent) {
        super(Component.translatable("worldrelay.menu.shared_worlds"));
        this.parent = parent;
    }

    /** 按 Esc 返回打开此页面的主菜单。 */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

}