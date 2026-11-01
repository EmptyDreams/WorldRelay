package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 共享世界列表与详情页面；从协调服务加载列表，加入按钮尚未接入操作。 */
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
    private static final Component HTTP_WARNING = Component.translatable("worldrelay.shared_worlds.http_warning");
    private static final Component HTTP_WARNING_LABEL = Component.translatable("worldrelay.shared_worlds.http_warning_label");

    private final Screen parent;
    private ListedWorld selectedWorld;
    private List<ListedWorld> worlds = List.of();
    private Component listMessage = Component.translatable(TRANSLATION_PREFIX + "loading");
    private boolean loadFailed;
    private WorldList worldList;
    private DetailsPanel detailsPanel;
    private Button joinButton;
    private boolean usesHttp;
    private int listRequestGeneration;
    private boolean listRefreshPending;

    public SharedWorldScreen(Screen parent) {
        super(Component.translatable("worldrelay.menu.shared_worlds"));
        this.parent = parent;
    }

    /** 每次进入列表（包括从设置页返回）时异步刷新列表和传输安全提示。 */
    @Override
    public void added() {
        super.added();
        this.listRefreshPending = true;
    }

    private void refreshWorldList(@NotNull Minecraft client) {
        int generation = ++this.listRequestGeneration;
        Path file = client.gameDirectory.toPath().resolve("config/worldrelay-client.json");
        String playerName = client.getUser().getName();
        this.worlds = List.of();
        this.selectedWorld = null;
        this.loadFailed = false;
        this.listMessage = Component.translatable(TRANSLATION_PREFIX + "loading");
        this.rebuildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try {
                var settings = ClientConnectionSettings.load(file);
                boolean http = !settings.serverAddress().isEmpty() && settings.usesHttp();
                client.execute(() -> {
                    if (generation == this.listRequestGeneration && client.screen == this) {
                        this.usesHttp = http;
                        this.rebuildWidgets();
                    }
                });
                return SharedWorldListClient.fetch(settings, playerName).stream()
                    .map(world -> new ListedWorld(world.worldId(), Component.literal(world.name()),
                        WorldStatus.valueOf(world.status()), world.hostName(),
                        world.playerCount() == null ? -1 : world.playerCount(),
                        world.lastCommittedAt() == null
                            ? Component.translatable(TRANSLATION_PREFIX + "never_committed")
                            : Component.literal(world.lastCommittedAt()))).toList();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((loaded, error) -> client.execute(() -> {
            if (generation != this.listRequestGeneration || client.screen != this) {
                return;
            }
            this.loadFailed = error != null;
            this.worlds = error == null ? loaded : List.of();
            this.selectedWorld = this.worlds.isEmpty() ? null : this.worlds.getFirst();
            this.listMessage = Component.translatable(TRANSLATION_PREFIX + (error != null ? "load_failed" : "empty"));
            this.rebuildWidgets();
        }));
    }

    @Override
    public void removed() {
        ++this.listRequestGeneration;
        super.removed();
    }

    @Override
    protected void init() {
        var client = Objects.requireNonNull(this.minecraft, "Screen must be initialized with a Minecraft client");
        int contentWidth = this.width - MARGIN * 2;
        int contentTop = CONTENT_TOP;
        int contentHeight = Math.max(40, this.height - contentTop - 54);
        int listWidth = (contentWidth - COLUMN_GAP) * 3 / 5;
        int detailsX = MARGIN + listWidth + COLUMN_GAP;
        // 详情滚动条绘制在控件右侧，额外预留其占用的宽度。
        int detailsWidth = this.width - MARGIN - detailsX - 8;
        int buttonGap = 6;
        int buttonWidth = (contentWidth - buttonGap * 2) / 3;
        int buttonY = this.height - 40;

        // 加入按钮暂用空回调，可用状态仍随所选世界变化。
        this.joinButton = Button.builder(Component.translatable(TRANSLATION_PREFIX + "join"), button -> {})
            .bounds(MARGIN, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build();
        this.detailsPanel = new DetailsPanel(detailsX, contentTop, detailsWidth, contentHeight);
        this.worldList = new WorldList(MARGIN, contentTop, listWidth, contentHeight);

        this.addRenderableWidget(worldList);
        this.addRenderableWidget(this.detailsPanel);
        this.addRenderableWidget(this.joinButton);
        int settingsX = MARGIN + buttonWidth + buttonGap;
        this.addRenderableWidget(Button.builder(Component.translatable(TRANSLATION_PREFIX + "settings"),
                button -> client.setScreen(new SharedWorldSettingsScreen(this)))
            .bounds(settingsX, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        int backX = MARGIN + (buttonWidth + buttonGap) * 2;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
            .bounds(backX, buttonY, this.width - MARGIN - backX, Button.DEFAULT_HEIGHT).build());

        this.joinButton.active = false;
        worldList.children().stream().filter(entry -> entry.world.equals(this.selectedWorld)).findFirst()
            .ifPresent(worldList::setSelected);
        this.setInitialFocus(worldList);
        if (this.listRefreshPending) {
            this.listRefreshPending = false;
            this.refreshWorldList(client);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, TEXT_COLOR);
        if (this.worlds.isEmpty()) {
            var lines = this.font.split(this.listMessage, Math.max(1, this.worldList.getWidth() - 16));
            int y = this.worldList.getY() + (this.worldList.getHeight() - lines.size() * (this.font.lineHeight + 1)) / 2;
            for (var line : lines) {
                graphics.drawCenteredString(this.font, line, this.worldList.getX() + this.worldList.getWidth() / 2,
                    y, this.loadFailed ? 0xFFFF5555 : SECONDARY_TEXT_COLOR);
                y += this.font.lineHeight + 1;
            }
        }
        graphics.drawString(this.font, Component.translatable(TRANSLATION_PREFIX + "list_title"), MARGIN, 44, TEXT_COLOR);
        graphics.drawString(this.font, Component.translatable(TRANSLATION_PREFIX + "details_title"),
            this.detailsPanel.getX(), 44, TEXT_COLOR);
        graphics.drawCenteredString(this.font, Component.translatable(TRANSLATION_PREFIX + "back_hint"),
            this.width / 2, this.height - 12, SECONDARY_TEXT_COLOR);
        if (this.usesHttp) {
            this.renderHttpWarning(graphics, mouseX, mouseY);
        }
    }

    /** 优先与标题同行；窄窗口使用标题下方的既有空隙，不移动列表或详情。 */
    private void renderHttpWarning(GuiGraphics graphics, int mouseX, int mouseY) {
        int badgeHeight = 16;
        int badgeWidth = this.font.width(HTTP_WARNING_LABEL) + 28;
        int x = this.width - MARGIN - badgeWidth;
        int titleRight = (this.width + this.font.width(this.title)) / 2;
        int y = x >= titleRight + 8 ? 8 : 26;
        int color = 0xFFFFC45C;

        fillRoundedRect(graphics, x, y, badgeWidth, badgeHeight, 4, 0xFFAD792D);
        fillRoundedRect(graphics, x + 1, y + 1, badgeWidth - 2, badgeHeight - 2, 3, 0xFF302719);

        // 直接绘制三角警告图标，避免依赖字体是否包含警告符号。
        int iconX = x + 5;
        int iconY = y + 3;
        for (int row = 0; row < 10; row++) {
            int halfWidth = row / 2;
            graphics.fill(iconX + 5 - halfWidth, iconY + row, iconX + 6 + halfWidth, iconY + row + 1, color);
        }
        graphics.fill(iconX + 5, iconY + 3, iconX + 6, iconY + 6, 0xFF302719);
        graphics.fill(iconX + 5, iconY + 7, iconX + 6, iconY + 8, 0xFF302719);
        graphics.drawString(this.font, HTTP_WARNING_LABEL, x + 21,
            y + (badgeHeight - this.font.lineHeight) / 2, color, false);

        if (mouseX >= x && mouseX < x + badgeWidth && mouseY >= y && mouseY < y + badgeHeight) {
            graphics.renderTooltip(this.font, this.font.split(HTTP_WARNING, Math.max(1, this.width - MARGIN * 2)), mouseX, mouseY);
        }
    }

    private static void fillRoundedRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        for (int row = 0; row < height; row++) {
            int edgeDistance = Math.min(row, height - row - 1);
            int inset = 0;
            if (edgeDistance < radius) {
                double dy = radius - edgeDistance - 0.5;
                inset = (int) Math.ceil(radius - Math.sqrt(radius * radius - dy * dy) - 0.5);
            }
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }

    /** 点击返回按钮或按 Esc，返回打开此页面的主菜单。 */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /** 只描述本页展示需要的数据，不代表已经实现协调服务或游戏连接。 */
    private record ListedWorld(String worldId, Component name, WorldStatus status, @Nullable String host, int players, Component lastSync) {

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
        RECOVERY_REQUIRED("recovery_required", false, 0xFFFFAA00),
        OWNED_OFFLINE("recovery_required", false, 0xFFFFAA00);

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
            worlds.forEach(world -> this.addEntry(new WorldEntry(world)));
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

            private final ListedWorld world;

            WorldEntry(ListedWorld world) {
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

        void show(ListedWorld world) {
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
        protected void updateWidgetNarration(@NotNull NarrationElementOutput output) {
            if (selectedWorld == null) {
                output.add(NarratedElementType.TITLE, listMessage);
                return;
            }
            output.add(NarratedElementType.TITLE, selectedWorld.name());
            output.add(NarratedElementType.HINT, selectedWorld.status().description());
        }

    }

    private record DetailLine(FormattedCharSequence text, int y, int color) {

    }

}
