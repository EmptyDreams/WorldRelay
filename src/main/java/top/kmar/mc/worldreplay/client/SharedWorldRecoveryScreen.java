package top.kmar.mc.worldreplay.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/** 旧资格失效后由玩家明确选择世界来源；返回不会删除本地进度。 */
final class SharedWorldRecoveryScreen extends Screen {

    private final Screen parent;
    private final boolean canUseLocal;
    private final boolean canSync;
    private final boolean running;
    private final Runnable useLocal;
    private final Runnable sync;

    SharedWorldRecoveryScreen(Screen parent, boolean canUseLocal, boolean canSync, boolean running, Runnable useLocal, Runnable sync) {
        super(Component.translatable("worldrelay.replacement.title"));
        this.parent = parent;
        this.canUseLocal = canUseLocal;
        this.canSync = canSync;
        this.running = running;
        this.useLocal = useLocal;
        this.sync = sync;
    }

    @Override
    protected void init() {
        var local = addRenderableWidget(Button.builder(Component.translatable("worldrelay.replacement.local"), button -> useLocal.run())
            .bounds(width / 2 - 155, height - 73, 150, 20).build());
        local.active = canUseLocal;
        var server = addRenderableWidget(Button.builder(Component.translatable(running ? "worldrelay.replacement.join" : "worldrelay.replacement.sync"), button -> sync.run())
            .bounds(width / 2 + 5, height - 73, 150, 20).build());
        server.active = canSync;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose()).bounds(width / 2 - 75, height - 45, 150, 20).build());
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFFFF);
        var message = Component.translatable(!canSync ? "worldrelay.replacement.waiting"
            : !canUseLocal && !running ? "worldrelay.replacement.server_only" : "worldrelay.replacement.message");
        var lines = font.split(message, Math.max(40, width - 60));
        int y = Math.max(42, (height - 80 - lines.size() * 12) / 2);
        for (var line : lines) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFFFF);
            y += 12;
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

}
