package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Callable;

/** 共享世界列表；首次创建经协调服务取得所有权后进入原版创建流程。 */
public final class SharedWorldScreen extends Screen {

    private static final String TRANSLATION_PREFIX = "worldrelay.shared_worlds.";
    private static final int MARGIN = 12;
    private static final int COLUMN_GAP = 12;
    private static final int CONTENT_TOP = 58;
    private static final int WORLD_ROW_HEIGHT = 32;
    private static final int JOIN_ICON_SIZE = 24;
    private static final int JOIN_ICON_OFFSET_X = 4;
    private static final int JOIN_ICON_OFFSET_Y = 2;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_TEXT_COLOR = 0xFFAAAAAA;
    private static final int PANEL_COLOR = 0x90000000;
    private static final int BORDER_COLOR = 0xFF555555;
    private static final Component HTTP_WARNING = Component.translatable("worldrelay.shared_worlds.http_warning");
    private static final Component HTTP_WARNING_LABEL = Component.translatable("worldrelay.shared_worlds.http_warning_label");
    private static final Component JOIN_LABEL = Component.translatable(TRANSLATION_PREFIX + "join");
    private static final ResourceLocation JOIN_SPRITE = ResourceLocation.withDefaultNamespace("world_list/join");
    private static final ResourceLocation JOIN_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace("world_list/join_highlighted");

    private final Screen parent;
    private ListedWorld selectedWorld;
    private List<ListedWorld> worlds = List.of();
    private Component listMessage = Component.translatable(TRANSLATION_PREFIX + "loading");
    private boolean loadFailed;
    private WorldList worldList;
    private DetailsPanel detailsPanel;
    private boolean usesHttp;
    private int listRequestGeneration;
    private boolean listRefreshPending;
    private boolean listLoading;
    private Button deleteLocalButton, importLocalButton, exportLocalButton;
    private LocalContext localContext;
    private LocalWorldFiles.State localState;
    private boolean localLoading;
    private int localRequestGeneration;

    private record LocalContext(Path game, ClientConnectionSettings settings, String user, UUID profile, String world, Path root) {
        LocalWorldSessionStore store() throws IOException {
            return new LocalWorldSessionStore(game, settings.serverUri().toString(), user, profile, world);
        }

        <T> CompletableFuture<T> run(Callable<T> action) {
            return SharedWorldCreation.localFiles(root, () -> {
                LocalWorldSessionStore.lockGame(game);
                return action.call();
            });
        }
    }

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
        if (this.listLoading) {
            return;
        }
        this.listLoading = true;
        String selectedId = this.selectedWorld == null ? null : this.selectedWorld.worldId();
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
                        world.lastCommittedAt() == null ? null : Instant.parse(world.lastCommittedAt()), world.allowOfflinePlayers())).toList();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((loaded, error) -> client.execute(() -> {
            if (generation != this.listRequestGeneration || client.screen != this) {
                return;
            }
            this.listLoading = false;
            this.loadFailed = error != null;
            this.worlds = error == null ? loaded : List.of();
            this.selectedWorld = this.worlds.stream().filter(world -> world.worldId().equals(selectedId)).findFirst()
                .orElse(this.worlds.isEmpty() ? null : this.worlds.getFirst());
            this.listMessage = Component.translatable(TRANSLATION_PREFIX + (error != null ? "load_failed" : "empty"));
            this.rebuildWidgets();
        }));
    }

    @Override
    public void removed() {
        ++this.listRequestGeneration;
        ++this.localRequestGeneration;
        this.listLoading = false;
        super.removed();
    }

    @Override
    protected void init() {
        var client = Objects.requireNonNull(this.minecraft, "Screen must be initialized with a Minecraft client");
        int contentWidth = this.width - MARGIN * 2;
        int contentTop = CONTENT_TOP;
        int contentHeight = Math.max(40, this.height - contentTop - 54);
        int buttonY = this.height - 40;
        int detailsHeight = Math.max(40, buttonY - 6 - contentTop);
        int listWidth = (contentWidth - COLUMN_GAP) * 3 / 5;
        int detailsX = MARGIN + listWidth + COLUMN_GAP;
        // 详情滚动条绘制在控件右侧，额外预留其占用的宽度。
        int detailsWidth = this.width - MARGIN - detailsX - 8;
        int buttonGap = 6;
        int buttonWidth = (listWidth - buttonGap * 2) / 3;

        this.detailsPanel = new DetailsPanel(detailsX, contentTop, detailsWidth, detailsHeight);
        this.worldList = new WorldList(MARGIN, contentTop, listWidth, contentHeight);

        this.addRenderableWidget(worldList);
        this.addRenderableWidget(this.detailsPanel);
        // 三个按钮及中间间距的总宽度与左侧列表一致，右侧留给详情。
        int settingsX = MARGIN;
        this.addRenderableWidget(Button.builder(Component.translatable(TRANSLATION_PREFIX + "settings"),
                button -> client.setScreen(new SharedWorldSettingsScreen(this)))
            .bounds(settingsX, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        int refreshX = settingsX + buttonWidth + buttonGap;
        var refreshButton = this.addRenderableWidget(Button.builder(Component.translatable(TRANSLATION_PREFIX + "refresh"),
                button -> this.refreshWorldList(client))
            .bounds(refreshX, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        refreshButton.active = !this.listLoading;
        int backX = refreshX + buttonWidth + buttonGap;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
            .bounds(backX, buttonY, listWidth - (buttonWidth + buttonGap) * 2, Button.DEFAULT_HEIGHT).build());

        this.deleteLocalButton = this.addRenderableWidget(new LocalFileButton(detailsX, buttonY, LocalFileAction.DELETE,
            button -> this.deleteLocal()));
        this.importLocalButton = this.addRenderableWidget(new LocalFileButton(detailsX + LocalFileButton.SIZE + buttonGap,
            buttonY, LocalFileAction.IMPORT, button -> this.importLocal()));
        this.exportLocalButton = this.addRenderableWidget(new LocalFileButton(detailsX + (LocalFileButton.SIZE + buttonGap) * 2,
            buttonY, LocalFileAction.EXPORT, button -> this.exportLocal()));
        this.localButtons();

        worldList.children().stream().filter(entry -> entry.world.equals(this.selectedWorld)).findFirst()
            .ifPresent(worldList::setSelected);
        this.setInitialFocus(worldList);
        if (this.listRefreshPending) {
            this.listRefreshPending = false;
            this.refreshWorldList(client);
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.detailsPanel.refreshTime();
        this.localButtons();
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

    private void localButtons() {
        if (deleteLocalButton == null) return;
        boolean ready = selectedWorld != null && localContext != null && !localLoading && !listLoading
            && !SharedWorldCreation.localBusy(localContext.root());
        deleteLocalButton.active = ready && localState != null && localState.present();
        importLocalButton.active = ready;
        exportLocalButton.active = ready && localState != null && localState.present();
    }

    private void loadLocal(ListedWorld world) {
        var client = Objects.requireNonNull(minecraft);
        int generation = ++localRequestGeneration;
        localLoading = true;
        localContext = null;
        localState = null;
        localButtons();
        Path game = client.gameDirectory.toPath();
        String user = client.getUser().getName();
        UUID profile = client.getUser().getProfileId();
        CompletableFuture.supplyAsync(() -> {
            try {
                var settings = ClientConnectionSettings.load(game.resolve("config/worldrelay-client.json"));
                Path root = LocalWorldSessionStore.location(game, settings.serverUri().toString(), user, profile, world.worldId());
                var context = new LocalContext(game, settings, user, profile, world.worldId(), root);
                return java.util.Map.entry(context, LocalWorldFiles.inspect(root));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, Util.ioPool()).whenComplete((result, failure) -> client.execute(() -> {
            if (generation != localRequestGeneration || client.screen != this || selectedWorld == null
                || !selectedWorld.worldId().equals(world.worldId())) return;
            localLoading = false;
            if (failure == null) {
                localContext = result.getKey();
                localState = result.getValue();
            }
            localButtons();
            detailsPanel.show(selectedWorld);
        }));
    }

    private void importLocal() {
        if (localContext == null || selectedWorld == null || minecraft == null) return;
        var context = localContext;
        minecraft.setScreen(new SharedWorldFileScreen(this, selectedWorld.name(), source -> context.run(() -> {
            LocalWorldFiles.importSnapshot(context.store(), source);
            return null;
        })));
    }

    private void exportLocal() {
        if (localContext == null || selectedWorld == null || minecraft == null) return;
        var context = localContext;
        minecraft.setScreen(SharedWorldFileScreen.forExport(this, selectedWorld.name(), (target, replace) -> context.run(() -> {
            LocalWorldFiles.exportSnapshot(context.store(), target, replace);
            return null;
        })));
    }

    private void deleteLocal() {
        if (localContext == null || localState == null || selectedWorld == null || minecraft == null) return;
        var context = localContext;
        var state = localState;
        var client = minecraft;
        var message = Component.translatable("worldrelay.local_world.delete_body", selectedWorld.name());
        if (state.unuploaded()) message.append("\n\n").append(Component.translatable("worldrelay.local_world.delete_unuploaded").withStyle(ChatFormatting.RED));
        if (state.ownershipPending()) message.append("\n\n").append(Component.translatable("worldrelay.local_world.delete_ownership"));
        var confirmation = new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                client.setScreen(this);
                return;
            }
            client.setScreen(new SharedWorldOperationScreen(this, "local_deleting", true, null));
            context.run(() -> {
                LocalWorldFiles.checkUnused(context.root());
                SharedWorldCreation.forgetLocal(context.root());
                LocalWorldFiles.delete(context.root(), state.unuploaded());
                return null;
            }).whenComplete((ignored, failure) -> client.execute(() -> {
                if (failure == null) client.setScreen(this);
                else {
                    top.kmar.mc.worldreplay.WorldRelay.LOGGER.warn("Local world deletion did not complete", failure);
                    client.setScreen(new SharedWorldOperationScreen(this, "local_delete_failed", false, null));
                }
            }));
        }, Component.translatable("worldrelay.local_world.delete_title"), message,
            Component.translatable("worldrelay.local_world.delete_confirm"), CommonComponents.GUI_CANCEL);
        client.setScreen(confirmation);
        confirmation.setDelay(20);
    }

    /** 只描述本页展示需要的数据，不代表已经实现协调服务或游戏连接。 */
    private record ListedWorld(String worldId, Component name, WorldStatus status, @Nullable String host, int players, @Nullable Instant lastSync, boolean allowOfflinePlayers) {

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
        PREPARING("preparing", true, 0xFFFFD080),
        STARTING("starting", true, 0xFFFFD080),
        RUNNING("running", true, 0xFF80D080),
        STOPPING("stopping", true, 0xFFFFD080),
        // 等待提交时选择加入只能进入等待流程，不能接管世界。
        COMMITTING("committing", true, 0xFFFFD080),
        RECOVERY_REQUIRED("recovery_required", true, 0xFFFFAA00),
        OWNED_OFFLINE("recovery_required", true, 0xFFFFAA00);

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

    /** 复用原版列表的选择、键盘导航和滚动行为，加入入口位于每行名称左侧。 */
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
            if (entry != null) {
                selectedWorld = entry.world;
                detailsPanel.show(selectedWorld);
                loadLocal(selectedWorld);
            }
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            WorldEntry entry = this.getSelected();
            if (entry != null && CommonInputs.selected(keyCode)) {
                entry.activateJoin();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        private final class WorldEntry extends ObjectSelectionList.Entry<WorldEntry> {

            private final ListedWorld world;
            private long lastClickTime;

            WorldEntry(ListedWorld world) {
                this.world = world;
            }

            @Override
            public void render(
                GuiGraphics graphics, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, boolean hovering, float partialTick
            ) {
                int iconX = left + JOIN_ICON_OFFSET_X;
                int iconY = top + JOIN_ICON_OFFSET_Y;
                boolean overJoin = hovering && this.isOverJoinIcon(mouseX, mouseY);
                graphics.fill(iconX, iconY, iconX + JOIN_ICON_SIZE, iconY + JOIN_ICON_SIZE, 0xFF343B30);
                graphics.renderOutline(iconX, iconY, JOIN_ICON_SIZE, JOIN_ICON_SIZE, BORDER_COLOR);
                graphics.blitSprite(overJoin && world.status().joinAllowed ? JOIN_HIGHLIGHTED_SPRITE : JOIN_SPRITE,
                    iconX, iconY, JOIN_ICON_SIZE, JOIN_ICON_SIZE);
                if (!world.status().joinAllowed) {
                    graphics.fill(iconX, iconY, iconX + JOIN_ICON_SIZE, iconY + JOIN_ICON_SIZE, 0x90000000);
                }
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
                long now = Util.getMillis();
                if (this.isOverJoinIcon(mouseX, mouseY) || now - lastClickTime < 250L) {
                    this.activateJoin();
                }
                this.lastClickTime = now;
                return true;
            }

            /** 使用当前滚动位置计算点击区域，避免滚动后点击到上一帧的图标坐标。 */
            private boolean isOverJoinIcon(double mouseX, double mouseY) {
                int index = WorldList.this.children().indexOf(this);
                int x = WorldList.this.getRowLeft() + JOIN_ICON_OFFSET_X;
                int y = WorldList.this.getRowTop(index) + JOIN_ICON_OFFSET_Y;
                return mouseX >= x && mouseX < x + JOIN_ICON_SIZE && mouseY >= y && mouseY < y + JOIN_ICON_SIZE;
            }

            private void activateJoin() {
                if (!world.status().joinAllowed) {
                    return;
                }
                WorldList.this.setSelected(this);
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                SharedWorldCreation.join(SharedWorldScreen.this, world.worldId(), world.name().getString());
            }

            @Override
            public @NotNull Component getNarration() {
                Component narration = world.name().copy().append(CommonComponents.NARRATION_SEPARATOR).append(world.listStatus());
                return world.status().joinAllowed
                    ? narration.copy().append(CommonComponents.NARRATION_SEPARATOR).append(JOIN_LABEL)
                    : narration;
            }

        }

    }

    /** 详情独立滚动，避免小窗口或较长的状态说明被底部按钮遮挡。 */
    private final class DetailsPanel extends AbstractScrollWidget {

        private static final int PADDING = 6;

        private final List<DetailLine> lines = new ArrayList<>();
        private int contentHeight;
        private Component lastSyncText = CommonComponents.EMPTY;
        private long nextTimeRefresh;

        DetailsPanel(int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable(TRANSLATION_PREFIX + "details_title"));
        }

        void show(ListedWorld world) {
            this.lines.clear();
            this.lastSyncText = WorldSyncTime.format(world.lastSync(), Instant.now(), ZoneId.systemDefault());
            int y = addParagraph(world.name(), TEXT_COLOR, PADDING, 2);
            y = addParagraph(world.status().label(), world.status().color, y, 6);
            y = addParagraph(Component.translatable(TRANSLATION_PREFIX + (world.allowOfflinePlayers() ? "offline_allowed" : "online_only")), SECONDARY_TEXT_COLOR, y, 2);
            y = addParagraph(Component.translatable(TRANSLATION_PREFIX + "host", world.hostName()), SECONDARY_TEXT_COLOR, y, 2);
            y = addParagraph(Component.translatable(TRANSLATION_PREFIX + "players", world.playerCount()), SECONDARY_TEXT_COLOR, y, 2);
            y = addParagraph(Component.translatable(TRANSLATION_PREFIX + "last_sync", this.lastSyncText), SECONDARY_TEXT_COLOR, y, 6);
            String local = localLoading ? "checking" : localContext == null ? "unavailable" : localState != null && localState.present() ? "present" : "absent";
            y = addParagraph(Component.translatable("worldrelay.local_world." + local), SECONDARY_TEXT_COLOR, y, 2);
            if (localState != null && localState.unuploaded()) y = addParagraph(Component.translatable("worldrelay.local_world.unuploaded"), 0xFFFFAA55, y, 6);
            this.contentHeight = addParagraph(world.status().description(), TEXT_COLOR, y, PADDING);
            this.setScrollAmount(0);
        }

        /** 每秒更新一次相对时间；文字变化时保留详情的滚动位置。 */
        void refreshTime() {
            long now = Util.getMillis();
            if (selectedWorld == null || now < this.nextTimeRefresh) {
                return;
            }
            this.nextTimeRefresh = now + 1_000;
            if (!this.lastSyncText.equals(WorldSyncTime.format(selectedWorld.lastSync(), Instant.now(), ZoneId.systemDefault()))) {
                double scroll = this.scrollAmount();
                this.show(selectedWorld);
                this.setScrollAmount(scroll);
            }
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

    private enum LocalFileAction { DELETE, IMPORT, EXPORT }

    /** 保留原版按钮的交互与朗读名称，仅将文字绘制替换为像素图标。 */
    private static final class LocalFileButton extends Button {
        private static final int SIZE = Button.DEFAULT_HEIGHT;
        private final LocalFileAction action;

        LocalFileButton(int x, int y, LocalFileAction action, OnPress onPress) {
            super(x, y, SIZE, SIZE,
                Component.translatable("worldrelay.local_world." + action.name().toLowerCase(java.util.Locale.ROOT)), onPress, DEFAULT_NARRATION);
            this.action = action;
            setTooltip(Tooltip.create(getMessage()));
        }

        @Override
        public void renderString(@NotNull GuiGraphics graphics, @NotNull Font font, int color) {
            int x = getX() + 4;
            int y = getY() + 4;
            if (action == LocalFileAction.DELETE) {
                // 垃圾桶：提手、桶盖、桶身和两条竖线。
                graphics.fill(x + 4, y, x + 8, y + 2, color);
                graphics.fill(x + 1, y + 2, x + 11, y + 3, color);
                graphics.fill(x + 2, y + 4, x + 3, y + 11, color);
                graphics.fill(x + 9, y + 4, x + 10, y + 11, color);
                graphics.fill(x + 3, y + 11, x + 9, y + 12, color);
                graphics.fill(x + 4, y + 5, x + 5, y + 10, color);
                graphics.fill(x + 7, y + 5, x + 8, y + 10, color);
            } else {
                // 同一托盘配向下/向上箭头，分别表示导入和导出。
                for (int row = 0; row < 8; row++) {
                    int arrowY = y + (action == LocalFileAction.EXPORT ? 7 - row : row);
                    if (row < 7) graphics.fill(x + 5, arrowY, x + 7, arrowY + 1, color);
                    if (row >= 4) {
                        graphics.fill(x + row - 2, arrowY, x + row, arrowY + 1, color);
                        graphics.fill(x + 12 - row, arrowY, x + 14 - row, arrowY + 1, color);
                    }
                }
                graphics.fill(x, y + 8, x + 1, y + 12, color);
                graphics.fill(x + 11, y + 8, x + 12, y + 12, color);
                graphics.fill(x + 1, y + 11, x + 11, y + 12, color);
            }
        }
    }

    private record DetailLine(FormattedCharSequence text, int y, int color) {

    }

}
