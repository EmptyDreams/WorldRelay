package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.mc.worldreplay.sync.RsyncMode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 共享世界连接设置；连接验证和文件读写在 IO 线程执行，控件更新回到客户端线程。 */
public final class SharedWorldSettingsScreen extends Screen {

    private static final String PREFIX = "worldrelay.settings.";
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_COLOR = 0xFFAAAAAA;
    private static final int ERROR_COLOR = 0xFFFF8080;
    private static final int GAP = 6;
    private static final int PROTOCOL_WIDTH = 70;
    private static final int PORT_WIDTH = 64;
    private static final int ROW_HEIGHT = 36;

    private enum SettingsTab {
        API, SYNC
    }

    private final Screen parent;
    // 草稿独立于控件保存，窗口缩放或重建控件时不丢失尚未保存的输入。
    private String protocolDraft = "https";
    private String domainDraft = "";
    private String portDraft = "443";
    private String tokenDraft = "";
    private RsyncMode rsyncModeDraft = RsyncMode.defaultMode();
    private String rsyncPathDraft = "";
    private String sshPathDraft = "";
    private SettingsTab selectedTab = SettingsTab.API;
    private final List<RsyncMode> availableModes = RsyncMode.availableModes();
    private final Map<RsyncMode, Button> modeButtons = new EnumMap<>(RsyncMode.class);
    private boolean loadStarted;
    private boolean loading = true;
    private boolean saving;
    private boolean showToken;
    private boolean protocolMenuOpen;
    private String ioError;
    private Component status = CommonComponents.EMPTY;
    private int formX;
    private int formY;
    private int formWidth;
    private Path settingsFile;
    private Button protocolButton;
    private Button httpsOption;
    private Button httpOption;
    private EditBox domainField;
    private EditBox portField;
    private EditBox tokenField;
    private EditBox playerNameField;
    private EditBox rsyncPathField;
    private EditBox sshPathField;
    private Button connectionTabButton;
    private Button syncTabButton;
    private Button visibilityButton;
    private Button saveButton;
    private Button cancelButton;

    public SharedWorldSettingsScreen(Screen parent) {
        super(Component.translatable(PREFIX + "title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Screen.init(Minecraft, ...) 在调用此方法前设置客户端实例。
        var client = Objects.requireNonNull(this.minecraft, "Screen must be initialized with a Minecraft client");
        this.formWidth = Math.min(360, this.width - 32);
        this.formX = (this.width - this.formWidth) / 2;
        this.formY = Math.max(70, (this.height - 180) / 2);
        this.settingsFile = client.gameDirectory.toPath().resolve("config/worldrelay-client.json");
        int domainWidth = this.formWidth - PROTOCOL_WIDTH - GAP * 2 - PORT_WIDTH;
        int portX = this.formX + this.formWidth - PORT_WIDTH;
        int tabWidth = (this.formWidth - GAP) / 2;
        this.connectionTabButton = this.addRenderableWidget(Button.builder(label("connection_tab"),
            button -> this.selectTab(SettingsTab.API)).bounds(this.formX, 42, tabWidth, 20).build());
        this.syncTabButton = this.addRenderableWidget(Button.builder(label("sync_tab"),
            button -> this.selectTab(SettingsTab.SYNC)).bounds(this.formX + tabWidth + GAP, 42,
            this.formWidth - tabWidth - GAP, 20).build());

        this.protocolButton = this.addRenderableWidget(Button.builder(this.protocolCaption(),
                button -> this.setProtocolMenuOpen(!this.protocolMenuOpen))
            .bounds(this.formX, this.formY + 12, PROTOCOL_WIDTH, 20).build());
        this.domainField = new EditBox(this.font, this.formX + PROTOCOL_WIDTH + GAP, this.formY + 12,
            domainWidth, 20, label("domain"));
        this.domainField.setMaxLength(512);
        this.domainField.setHint(Component.literal("relay.example.com"));
        this.domainField.setFilter(value -> value.indexOf(':') < 0 && value.indexOf('/') < 0 && value.indexOf('\\') < 0);
        this.domainField.setValue(this.domainDraft);
        this.domainField.setResponder(value -> {
            this.domainDraft = value;
            this.inputChanged();
        });
        this.addRenderableWidget(this.domainField);

        this.portField = new EditBox(this.font, portX, this.formY + 12, PORT_WIDTH, 20, label("port"));
        this.portField.setMaxLength(5);
        this.portField.setFilter(value -> value.matches("[0-9]*"));
        this.portField.setValue(this.portDraft);
        this.portField.setResponder(value -> {
            this.portDraft = value;
            this.inputChanged();
        });
        this.addRenderableWidget(this.portField);

        this.playerNameField = new EditBox(this.font, this.formX, this.formY + ROW_HEIGHT + 12,
            this.formWidth, 20, label("player_name"));
        this.playerNameField.setMaxLength(64);
        this.playerNameField.setValue(client.getUser().getName());
        this.playerNameField.setEditable(false);
        this.playerNameField.setTextColorUneditable(SECONDARY_COLOR);
        this.playerNameField.active = false;
        // 仅绘制玩家名，不加入交互控件列表，禁止点击和键盘聚焦。
        this.addRenderableOnly(this.playerNameField);

        this.tokenField = new EditBox(this.font, this.formX, this.formY + ROW_HEIGHT * 2 + 12,
            this.formWidth - 56, 20, label("token")) {
            @Override
            protected @NotNull MutableComponent createNarrationMessage() {
                // 不朗读凭据，即使用户主动显示 token。
                return this.getMessage().copy();
            }

            @Override
            public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                // 遮蔽字符与原字符宽度可能不同，保证内容不会绘制到输入框外。
                graphics.enableScissor(this.getX(), this.getY(), this.getRight(), this.getBottom());
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
                graphics.disableScissor();
            }
        };
        this.tokenField.setMaxLength(ClientConnectionSettings.MAX_TOKEN_LENGTH);
        this.tokenField.setFormatter((value, position) ->
            FormattedCharSequence.forward(this.showToken ? value : "*".repeat(value.length()), Style.EMPTY));
        this.tokenField.setValue(this.tokenDraft);
        this.tokenField.setResponder(value -> {
            this.tokenDraft = value;
            this.inputChanged();
        });
        this.addRenderableWidget(this.tokenField);
        this.visibilityButton = this.addRenderableWidget(Button.builder(label(this.showToken ? "hide" : "show"), button -> {
            this.showToken = !this.showToken;
            button.setMessage(label(this.showToken ? "hide" : "show"));
        }).bounds(this.formX + this.formWidth - 50, this.formY + ROW_HEIGHT * 2 + 12, 50, 20).build());

        this.modeButtons.clear();
        int modeWidth = (this.formWidth - GAP * (this.availableModes.size() - 1)) / this.availableModes.size();
        for (int index = 0; index < this.availableModes.size(); index++) {
            RsyncMode mode = this.availableModes.get(index);
            Button button = this.addRenderableWidget(Button.builder(modeLabel(mode), clicked -> {
                this.rsyncModeDraft = mode;
                this.inputChanged();
            }).bounds(this.formX + index * (modeWidth + GAP), this.formY + 12, modeWidth, 20).build());
            button.setTooltip(Tooltip.create(label("rsync_mode." + mode.name().toLowerCase(Locale.ROOT) + "_tooltip")));
            this.modeButtons.put(mode, button);
        }

        // 自定义路径并排占一行，使连接与工具设置在最小窗口中也能完整显示。
        int pathWidth = (this.formWidth - GAP) / 2;
        this.rsyncPathField = new EditBox(this.font, this.formX, this.formY + ROW_HEIGHT + 12,
            pathWidth, 20, label("rsync_path"));
        this.rsyncPathField.setMaxLength(ClientConnectionSettings.MAX_RSYNC_PATH_LENGTH);
        this.rsyncPathField.setHint(label("executable_path_hint"));
        this.rsyncPathField.setTooltip(Tooltip.create(label("rsync_path_tooltip")));
        this.rsyncPathField.setValue(this.rsyncPathDraft);
        this.rsyncPathField.setResponder(value -> {
            this.rsyncPathDraft = value;
            this.inputChanged();
        });
        this.addRenderableWidget(this.rsyncPathField);

        this.sshPathField = new EditBox(this.font, this.formX + pathWidth + GAP, this.formY + ROW_HEIGHT + 12,
            this.formWidth - pathWidth - GAP, 20, label("ssh_path"));
        this.sshPathField.setMaxLength(ClientConnectionSettings.MAX_RSYNC_PATH_LENGTH);
        this.sshPathField.setHint(label("executable_path_hint"));
        this.sshPathField.setTooltip(Tooltip.create(label("ssh_path_tooltip")));
        this.sshPathField.setValue(this.sshPathDraft);
        this.sshPathField.setResponder(value -> {
            this.sshPathDraft = value;
            this.inputChanged();
        });
        this.addRenderableWidget(this.sshPathField);

        int buttonWidth = (this.formWidth - GAP) / 2;
        this.saveButton = this.addRenderableWidget(Button.builder(label("save"), button -> this.saveSettings(client))
            .bounds(this.formX, this.height - 28, buttonWidth, 20).build());
        this.cancelButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
            .bounds(this.formX + buttonWidth + GAP, this.height - 28, this.formWidth - buttonWidth - GAP, 20).build());
        // 选项参与输入与旁白，在 render 末尾绘制，覆盖下方控件及标签。
        this.httpsOption = this.addWidget(Button.builder(Component.literal("HTTPS"), button -> this.selectProtocol("https"))
            .bounds(this.formX, this.formY + 34, PROTOCOL_WIDTH, 20).build());
        this.httpOption = this.addWidget(Button.builder(Component.literal("HTTP"), button -> this.selectProtocol("http"))
            .bounds(this.formX, this.formY + 54, PROTOCOL_WIDTH, 20).build());
        this.setProtocolMenuOpen(false);
        this.updateControls();
        if (!this.loadStarted) {
            this.loadSettings(client);
        }
    }

    private static Component label(String key) {
        return Component.translatable(PREFIX + key);
    }

    private static Component modeLabel(RsyncMode mode) {
        return label("rsync_mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private void selectTab(SettingsTab tab) {
        this.selectedTab = tab;
        this.setProtocolMenuOpen(false);
        this.clearFocus();
        this.updateControls();
    }

    private Component protocolCaption() {
        return Component.translatable(PREFIX + "protocol_selection", this.protocolDraft.equals("http") ? "HTTP" : "HTTPS");
    }

    private void setProtocolMenuOpen(boolean open) {
        this.protocolMenuOpen = open;
        this.httpsOption.visible = open;
        this.httpOption.visible = open;
        if (open) {
            this.setInitialFocus(this.protocolDraft.equals("http") ? this.httpOption : this.httpsOption);
        } else if (this.getFocused() == this.httpsOption || this.getFocused() == this.httpOption) {
            this.clearFocus();
            if (this.protocolButton.active) {
                this.setInitialFocus(this.protocolButton);
            }
        }
    }

    private void selectProtocol(String protocol) {
        this.protocolDraft = protocol;
        this.protocolButton.setMessage(this.protocolCaption());
        this.setProtocolMenuOpen(false);
        this.inputChanged();
    }

    private ClientConnectionSettings draftSettings() {
        String domain = this.domainDraft.strip();
        String address = domain.isEmpty() ? "" : this.protocolDraft + "://" + domain;
        return ClientConnectionSettings.fromInput(address, this.portDraft, this.tokenDraft,
            this.rsyncModeDraft, this.rsyncPathDraft, this.sshPathDraft);
    }

    private void inputChanged() {
        this.ioError = null;
        this.updateControls();
    }

    private void updateControls() {
        boolean busy = this.loading || this.saving;
        boolean connection = this.selectedTab == SettingsTab.API;
        boolean sync = this.selectedTab == SettingsTab.SYNC;
        boolean custom = sync && this.rsyncModeDraft == RsyncMode.CUSTOM;
        this.connectionTabButton.active = !busy && !connection;
        this.syncTabButton.active = !busy && !sync;
        this.protocolButton.visible = connection;
        this.domainField.visible = connection;
        this.portField.visible = connection;
        this.playerNameField.visible = connection;
        this.tokenField.visible = connection;
        this.visibilityButton.visible = connection;
        this.protocolButton.active = !busy && connection;
        this.httpsOption.active = !busy;
        this.httpOption.active = !busy;
        if (busy || !connection) {
            this.setProtocolMenuOpen(false);
        }
        this.domainField.setEditable(!busy && connection);
        this.portField.setEditable(!busy && connection);
        this.tokenField.setEditable(!busy && connection);
        this.rsyncPathField.visible = custom;
        this.sshPathField.visible = custom;
        this.rsyncPathField.setEditable(!busy && custom);
        this.sshPathField.setEditable(!busy && custom);
        this.rsyncPathField.active = !busy && custom;
        this.sshPathField.active = !busy && custom;
        this.domainField.active = !busy && connection;
        this.portField.active = !busy && connection;
        this.tokenField.active = !busy && connection;
        for (var entry : this.modeButtons.entrySet()) {
            entry.getValue().visible = sync;
            entry.getValue().active = !busy && sync && entry.getKey() != this.rsyncModeDraft;
        }
        this.visibilityButton.active = !busy && connection;
        this.cancelButton.active = !this.saving;
        String validationError = null;
        try {
            this.draftSettings();
        } catch (ClientConnectionSettings.ValidationException e) {
            validationError = e.translationKey();
        }
        this.saveButton.active = !busy && validationError == null;
        this.status = this.loading ? label("loading") : this.saving ? label("saving")
            : this.ioError != null ? label(this.ioError)
            : validationError != null ? Component.translatable(validationError) : CommonComponents.EMPTY;
    }

    private void loadSettings(@NotNull Minecraft client) {
        this.loadStarted = true;
        Path file = this.settingsFile;
        CompletableFuture.supplyAsync(() -> {
            try {
                return ClientConnectionSettings.load(file);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((settings, error) -> client.execute(() -> {
            this.loading = false;
            if (error == null) {
                if (!settings.serverAddress().isEmpty()) {
                    URI address = URI.create(settings.serverAddress());
                    this.protocolDraft = Objects.requireNonNull(address.getScheme());
                    this.domainField.setValue(Objects.requireNonNull(address.getRawAuthority()));
                } else {
                    this.domainField.setValue("");
                }
                this.protocolButton.setMessage(this.protocolCaption());
                this.portField.setValue(Integer.toString(settings.serverPort()));
                this.tokenField.setValue(settings.token());
                this.rsyncModeDraft = settings.rsyncMode();
                this.rsyncPathField.setValue(settings.rsyncPath());
                this.sshPathField.setValue(settings.sshPath());
            } else {
                this.ioError = "error.load_failed";
            }
            this.updateControls();
            if (client.screen == this) {
                this.setInitialFocus(switch (this.selectedTab) {
                    case API -> this.domainField;
                    case SYNC -> this.modeButtons.get(this.rsyncModeDraft);
                });
            }
        }));
    }

    private void saveSettings(@NotNull Minecraft client) {
        if (!this.saveButton.active) {
            return;
        }
        var settings = this.draftSettings();
        String playerName = client.getUser().getName();
        Path file = this.settingsFile;
        this.saving = true;
        this.ioError = null;
        this.updateControls();
        CompletableFuture.runAsync(() -> {
            try {
                ClientSettingsVerifier.verifyAndSave(settings, playerName, file);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((ignored, error) -> client.execute(() -> {
            this.saving = false;
            if (error == null) {
                if (client.screen == this) {
                    client.setScreen(this.parent);
                }
            } else {
                this.ioError = ClientSettingsVerifier.errorKey(error);
                WorldRelay.LOGGER.warn("Shared-world settings failed ({}): {}",
                    this.ioError, ClientSettingsVerifier.diagnosticSummary(error));
                this.updateControls();
            }
        }));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.protocolMenuOpen) {
            if (this.httpsOption.mouseClicked(mouseX, mouseY, button) || this.httpOption.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (!this.protocolButton.isMouseOver(mouseX, mouseY)) {
                this.setProtocolMenuOpen(false);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.protocolMenuOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.setProtocolMenuOpen(false);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
                boolean onHttps = this.getFocused() == this.httpsOption
                    || (this.getFocused() != this.httpOption && this.protocolDraft.equals("https"));
                this.setInitialFocus(onHttps ? this.httpOption : this.httpsOption);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                this.setProtocolMenuOpen(false);
            }
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (this.protocolMenuOpen && this.getFocused() != this.protocolButton
            && this.getFocused() != this.httpsOption && this.getFocused() != this.httpOption) {
            this.setProtocolMenuOpen(false);
        }
        return handled;
    }

    @Override
    public void onClose() {
        if (!this.saving && this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, TEXT_COLOR);
        graphics.drawCenteredString(this.font, label("subtitle"), this.width / 2, 28, SECONDARY_COLOR);
        if (this.selectedTab == SettingsTab.SYNC) {
            graphics.drawString(this.font, Component.translatable(PREFIX + "rsync_mode", modeLabel(this.rsyncModeDraft)),
                this.formX, this.formY, TEXT_COLOR);
            if (this.rsyncModeDraft == RsyncMode.CUSTOM) {
                graphics.drawString(this.font, label("rsync_path"), this.formX, this.formY + ROW_HEIGHT, TEXT_COLOR);
                graphics.drawString(this.font, label("ssh_path"), this.sshPathField.getX(), this.formY + ROW_HEIGHT, TEXT_COLOR);
            } else {
                int descriptionY = this.formY + ROW_HEIGHT;
                Component description = label("rsync_mode." + this.rsyncModeDraft.name().toLowerCase(Locale.ROOT) + "_tooltip");
                for (var line : this.font.split(description, this.formWidth)) {
                    graphics.drawString(this.font, line, this.formX, descriptionY, SECONDARY_COLOR);
                    descriptionY += this.font.lineHeight + 1;
                }
            }
        } else {
            graphics.drawString(this.font, label("protocol"), this.formX, this.formY, TEXT_COLOR);
            graphics.drawString(this.font, label("domain"), this.formX + PROTOCOL_WIDTH + GAP, this.formY, TEXT_COLOR);
            graphics.drawString(this.font, label("port"), this.formX + this.formWidth - PORT_WIDTH, this.formY, TEXT_COLOR);
            graphics.drawString(this.font, label("player_name"), this.formX, this.formY + ROW_HEIGHT, TEXT_COLOR);
            graphics.drawString(this.font, label("token"), this.formX, this.formY + ROW_HEIGHT * 2, TEXT_COLOR);
        }
        int y = this.formY + ROW_HEIGHT * 3 + 4;
        int color = this.loading || this.saving ? SECONDARY_COLOR : ERROR_COLOR;
        for (var line : this.font.split(this.status, this.formWidth)) {
            graphics.drawString(this.font, line, this.formX, y, color);
            y += this.font.lineHeight + 1;
        }
        if (this.protocolMenuOpen) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.fill(this.formX - 1, this.formY + 33, this.formX + PROTOCOL_WIDTH + 1, this.formY + 75, 0xFF202020);
            this.httpsOption.render(graphics, mouseX, mouseY, partialTick);
            this.httpOption.render(graphics, mouseX, mouseY, partialTick);
            graphics.pose().popPose();
        }
    }

}
