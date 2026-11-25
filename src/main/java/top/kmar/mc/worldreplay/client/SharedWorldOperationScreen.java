package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
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
    private final WorldTransferProgress progress;

    SharedWorldOperationScreen(Screen parent, String message, boolean pending, Runnable retry) {
        this(parent, message, pending, retry, null);
    }

    SharedWorldOperationScreen(Screen parent, String message, boolean pending, Runnable retry, WorldTransferProgress progress) {
        super(Component.translatable("worldrelay.menu.shared_worlds"));
        this.parent = parent;
        this.message = Component.translatable("worldrelay.creation." + message);
        this.pending = pending;
        this.retry = retry;
        this.progress = progress;
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
        int contentHeight = lines.size() * 12 + (progress == null ? 0 : 34);
        int y = Math.max(45, (height - contentHeight) / 2);
        for (var line : lines) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFFFF);
            y += 12;
        }
        if (progress != null) renderProgress(graphics, y + 6);
    }

    private void renderProgress(GuiGraphics graphics, int y) {
        var state = progress.state();
        var sample = state.sample();
        int barWidth = Math.clamp(width - 40, 12, 240);
        int left = (width - barWidth) / 2;
        graphics.fill(left - 1, y - 1, left + barWidth + 1, y + 9, 0xFF606060);
        graphics.fill(left, y, left + barWidth, y + 8, 0xFF202020);
        Component status;
        if (sample != null) {
            int filled = barWidth * sample.percent() / 100;
            graphics.fill(left, y, left + filled, y + 8, 0xFF5AABDF);
            status = Component.translatable("worldrelay.transfer.progress", sample.percent() + "%", state.currentRate());
        } else {
            int segment = barWidth / 4;
            int travel = barWidth - segment;
            int distance = (int) (Util.getMillis() / 8 % (2 * travel));
            int start = left + Math.min(distance, 2 * travel - distance);
            graphics.fill(start, y, start + segment, y + 8, 0xFF5AABDF);
            status = Component.translatable(switch (state.phase()) {
                case PREPARING -> "worldrelay.transfer.preparing";
                case TRANSFERRING -> "worldrelay.transfer.transferring";
                case VERIFYING -> "worldrelay.transfer.verifying";
                case PUBLISHING -> "worldrelay.transfer.publishing";
            });
        }
        graphics.drawCenteredString(font, status, width / 2, y + 14, 0xFFDDDDDD);
    }

    @Override public boolean shouldCloseOnEsc() { return !pending; }
    @Override public void onClose() { if (!pending && minecraft != null) minecraft.setScreen(parent); }
}