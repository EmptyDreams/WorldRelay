package top.kmar.mc.worldreplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 共享世界列表与详情页面；当前使用示例数据，加入和新建按钮尚未接入操作。 */
public final class SharedWorldScreen extends Screen {

    private static final String TRANSLATION_PREFIX = "worldrelay.shared_worlds.";
    private static final int MARGIN = 12;
    private static final int COLUMN_GAP = 12;
    private static final int CONTENT_TOP = 58;
    private static final int WORLD_ROW_HEIGHT = 32;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_TEXT_COLOR = 0xFFAAAAAA;
    private static final int PANEL_COLOR = 0x90000000;
    private static final int BORDER_COLOR = 0xFF555555;

    // TODO: 临时示例数据仅用于查看布局和状态，后续由世界列表接口替换。
    private static final Component PREVIEW_NOTICE = Component.literal("示例列表 · 世界服务尚未接入");
    private static final List<PreviewWorld> PREVIEW_WORLDS = List.of(
        new PreviewWorld(Component.literal("我们的生存世界"), WorldStatus.RUNNING,
            "小明", 2, Component.literal("今天 18:40")),
        new PreviewWorld(Component.literal("建筑练习场"), WorldStatus.FREE,
            null, 0, Component.literal("昨天 21:16")),
        new PreviewWorld(Component.literal("机械与铁路"), WorldStatus.RECOVERY_REQUIRED,
            "小雨", -1, Component.literal("今天 17:22")),
        new PreviewWorld(Component.literal("朋友的冒险"), WorldStatus.COMMITTING,
            "阿木", -1, Component.literal("今天 16:05"))
    );

    private final Screen parent;
    private PreviewWorld selectedWorld = PREVIEW_WORLDS.getFirst();
    private DetailsPanel detailsPanel;
    private Button joinButton;

    public SharedWorldScreen(Screen parent) {
        super(Component.translatable("worldrelay.menu.shared_worlds"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var client = Objects.requireNonNull(this.minecraft, "Screen must be initialized with a Minecraft client");
        int contentWidth = this.width - MARGIN * 2;
        int contentHeight = Math.max(40, this.height - CONTENT_TOP - 54);
        int listWidth = (contentWidth - COLUMN_GAP) * 3 / 5;
        int detailsX = MARGIN + listWidth + COLUMN_GAP;
        // 详情滚动条绘制在控件右侧，额外预留其占用的宽度。
        int detailsWidth = this.width - MARGIN - detailsX - 8;
        int buttonGap = 6;
        int buttonWidth = (contentWidth - buttonGap * 3) / 4;
        int buttonY = this.height - 40;

        // 加入和新建按钮暂用空回调；加入按钮的可用状态仍随所选世界变化。
        Button.OnPress placeholder = button -> {};
        this.joinButton = Button.builder(Component.translatable(TRANSLATION_PREFIX + "join"), placeholder)
            .bounds(MARGIN, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build();
        this.detailsPanel = new DetailsPanel(detailsX, CONTENT_TOP, detailsWidth, contentHeight);
        WorldList worldList = new WorldList(MARGIN, CONTENT_TOP, listWidth, contentHeight);

        this.addRenderableWidget(worldList);
        this.addRenderableWidget(this.detailsPanel);
        this.addRenderableWidget(this.joinButton);
        this.addRenderableWidget(Button.builder(Component.translatable(TRANSLATION_PREFIX + "create"), placeholder)
            .bounds(MARGIN + buttonWidth + buttonGap, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        int backX = MARGIN + (buttonWidth + buttonGap) * 2;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
            .bounds(backX, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        int settingsX = MARGIN + (buttonWidth + buttonGap) * 3;
        this.addRenderableWidget(Button.builder(Component.translatable(TRANSLATION_PREFIX + "settings"),
                button -> client.setScreen(new SharedWorldSettingsScreen(this)))
            .bounds(settingsX, buttonY, this.width - MARGIN - settingsX, Button.DEFAULT_HEIGHT).build());

        worldList.children().stream().filter(entry -> entry.world.equals(this.selectedWorld)).findFirst()
            .ifPresent(worldList::setSelected);
        this.setInitialFocus(worldList);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, TEXT_COLOR);
        graphics.drawCenteredString(this.font, PREVIEW_NOTICE,
            this.width / 2, 28, SECONDARY_TEXT_COLOR);
        graphics.drawString(this.font, Component.translatable(TRANSLATION_PREFIX + "list_title"), MARGIN, 44, TEXT_COLOR);
        graphics.drawString(this.font, Component.translatable(TRANSLATION_PREFIX + "details_title"),
            this.detailsPanel.getX(), 44, TEXT_COLOR);
        graphics.drawCenteredString(this.font, Component.translatable(TRANSLATION_PREFIX + "back_hint"),
            this.width / 2, this.height - 12, SECONDARY_TEXT_COLOR);
    }

    /** 点击返回按钮或按 Esc，返回打开此页面的主菜单。 */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /** 只描述本页展示需要的数据，不代表已经实现协调服务或游戏连接。 */
    private record PreviewWorld(Component name, WorldStatus status, @Nullable String host, int players, Component lastSync) {

        Component hostName() {
            return host == null ? Component.translatable(TRANSLATION_PREFIX + "no_host") : Component.literal(host);
        }

        Component playerCount() {
            return players < 0 ? Component.literal("?") : Component.translatable(TRANSLATION_PREFIX + "player_count", players);
        }

        Component listStatus() {
            return status == WorldStatus.RUNNING
                ? Component.translatable(TRANSLATION_PREFIX + "hosting_summary", hostName(), playerCount())
                : status.label();
        }

    }

    /** 只控制页面展示和按钮可用性，不能据此获取或释放世界所有权。 */
    private enum WorldStatus {
        FREE("free", true, 0xFF80D080),
        RUNNING("running", true, 0xFF80D080),
        // 同步中允许选择加入，后续操作应进入可取消的等待流程，不能直接接管世界。
        COMMITTING("committing", true, 0xFFFFD080),
        RECOVERY_REQUIRED("recovery_required", false, 0xFFFFAA00);

        private final String key;
        private final boolean joinAllowed;
        private final int color;

        WorldStatus(String key, boolean joinAllowed, int color) {
            this.key = key;
            this.joinAllowed = joinAllowed;
            this.color = color;
        }

        Component label() {
            return Component.translatable(TRANSLATION_PREFIX + "status." + key);
        }

        Component description() {
            return Component.translatable(TRANSLATION_PREFIX + "description." + key);
        }
    }

    /** 复用原版列表的选择、键盘导航和滚动行为。 */
    private final class WorldList extends ObjectSelectionList<WorldList.WorldEntry> {

        WorldList(int x, int y, int width, int height) {
            super(Minecraft.getInstance(), width, height, y, WORLD_ROW_HEIGHT);
            this.setX(x);
            PREVIEW_WORLDS.forEach(world -> this.addEntry(new WorldEntry(world)));
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 16;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getX() + this.getWidth() - 6;
        }

        @Override
        public void setSelected(@Nullable WorldEntry entry) {
            super.setSelected(entry);
            joinButton.active = entry != null && entry.world.status().joinAllowed;
            if (entry != null) {
                selectedWorld = entry.world;
                detailsPanel.show(selectedWorld);
            }
        }

        private final class WorldEntry extends ObjectSelectionList.Entry<WorldEntry> {

            private final PreviewWorld world;

            WorldEntry(PreviewWorld world) {
                this.world = world;
            }

            @Override
            public void render(
                GuiGraphics graphics, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, boolean hovering, float partialTick
            ) {
                int iconSize = 24;
                int iconX = left + 4;
                int iconY = top + 2;
                graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF343B30);
                graphics.renderOutline(iconX, iconY, iconSize, iconSize, BORDER_COLOR);
                graphics.drawCenteredString(font, world.name().getString().substring(0, 1),
                    iconX + iconSize / 2, iconY + (iconSize - font.lineHeight) / 2, TEXT_COLOR);
                int textX = left + 34;
                int textWidth = width - 38;
                // 列表只保留名称和状态各一行，完整信息在右侧详情中查看。
                graphics.drawString(font, font.plainSubstrByWidth(world.name().getString(), textWidth), textX, top + 3, TEXT_COLOR);
                graphics.drawString(font, font.plainSubstrByWidth(world.listStatus().getString(), textWidth),
                    textX, top + font.lineHeight + 6, world.status().color);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button != 0) {
                    return false;
                }
                WorldList.this.setSelected(this);
                return true;
            }

            @Override
            public @NotNull Component getNarration() {
                return world.name().copy().append(CommonComponents.NARRATION_SEPARATOR).append(world.listStatus());
            }

        }

    }

    /** 详情独立滚动，避免小窗口或较长的状态说明被底部按钮遮挡。 */
    private final class DetailsPanel extends AbstractScrollWidget {

        private static final int PADDING = 6;

        private final List<DetailLine> lines = new ArrayList<>();
        private int contentHeight;

        DetailsPanel(int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable(TRANSLATION_PREFIX + "details_title"));
        }

        void show(PreviewWorld world) {
            this.lines.clear();
            int y = addParagraph(world.name(), TEXT_COLOR, PADDING, 2);
            y = addParagraph(world.status().label(), world.status().color, y, 6);
            y = addParagraph(Component.translatable(TRANSLATION_PREFIX + "host", world.hostName()), SECONDARY_TEXT_COLOR, y, 2);
            y = addParagraph(Component.translatable(TRANSLATION_PREFIX + "players", world.playerCount()), SECONDARY_TEXT_COLOR, y, 2);
            y = addParagraph(Component.translatable(TRANSLATION_PREFIX + "last_sync", world.lastSync()), SECONDARY_TEXT_COLOR, y, 6);
            this.contentHeight = addParagraph(world.status().description(), TEXT_COLOR, y, PADDING);
            this.setScrollAmount(0);
        }

        private int addParagraph(Component text, int color, int y, int spacing) {
            for (FormattedCharSequence line : font.split(text, this.getWidth() - PADDING * 2)) {
                this.lines.add(new DetailLine(line, y, color));
                y += font.lineHeight + 1;
            }
            return y + spacing;
        }

        @Override
        protected int getInnerHeight() {
            return this.contentHeight;
        }

        @Override
        protected double scrollRate() {
            return font.lineHeight * 2;
        }

        @Override
        protected void renderBackground(GuiGraphics graphics) {
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), PANEL_COLOR);
            graphics.renderOutline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), BORDER_COLOR);
        }

        @Override
        protected void renderContents(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            for (DetailLine line : this.lines) {
                graphics.drawString(font, line.text(), this.getX() + PADDING, this.getY() + line.y(), line.color());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, selectedWorld.name());
            output.add(NarratedElementType.HINT, selectedWorld.status().description());
        }

    }

    private record DetailLine(FormattedCharSequence text, int y, int color) {

    }

}
