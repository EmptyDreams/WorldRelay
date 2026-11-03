package top.kmar.mc.worldreplay.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/** 后台操作不可通过 Esc 隐式取消；只有明确的返回／重试操作。 */
final class SharedWorldOperationScreen extends Screen {
    private final Screen parent;
    private final Component message;
    private final boolean pending;
    private final Runnable retry;

    SharedWorldOperationScreen(Screen parent, String message, boolean pending, Runnable retry) {
        super(Component.translatable("worldrelay.menu.shared_worlds"));
        this.parent = parent;
        this.message = Component.translatable("worldrelay.creation." + message);
        this.pending = pending;
        this.retry = retry;
    }

    @Override
    protected void init() {
        if (pending) return;
        int y = this.height - 45;
        if (retry != null) {
            addRenderableWidget(Button.builder(Component.translatable("worldrelay.creation.retry"), b -> retry.run())
                .bounds(width / 2 - 155, y, 150, 20).build());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
            .bounds(retry == null ? width / 2 - 75 : width / 2 + 5, y, 150, 20).build());
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFFFF);
        var lines = font.split(message, Math.max(40, width - 60));
        int y = Math.max(45, (height - lines.size() * 12) / 2);
        for (var line : lines) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFFFF);
            y += 12;
        }
    }

    @Override public boolean shouldCloseOnEsc() { return !pending; }
    @Override public void onClose() { if (!pending && minecraft != null) minecraft.setScreen(parent); }
}
