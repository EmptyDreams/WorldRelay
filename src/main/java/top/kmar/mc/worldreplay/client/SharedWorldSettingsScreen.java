package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 共享世界连接设置；文件读写在 IO 线程执行，控件更新回到客户端线程。 */
public final class SharedWorldSettingsScreen extends Screen {

    private static final String PREFIX = "worldrelay.settings.";
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_COLOR = 0xFFAAAAAA;
    private static final int ERROR_COLOR = 0xFFFF8080;
    private static final int GAP = 6;
    private static final int SCHEME_WIDTH = 52;
    private static final int PORT_WIDTH = 64;

    private final Screen parent;
    // 草稿独立于控件保存，窗口缩放或重建控件时不丢失尚未保存的输入。
    private String domainDraft = "";
    private String portDraft = "443";
    private String tokenDraft = "";
    private boolean loadStarted;
    private boolean loading = true;
    private boolean saving;
    private boolean showToken;
    private String ioError;
    private Component status = CommonComponents.EMPTY;
    private int formX;
    private int formY;
    private int formWidth;
    private Path settingsFile;
    private EditBox domainField;
    private EditBox portField;
    private EditBox tokenField;
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
        this.formY = Math.max(46, (this.height - 210) / 2);
        this.settingsFile = client.gameDirectory.toPath().resolve("config/worldrelay-client.json");
        int domainWidth = this.formWidth - SCHEME_WIDTH - GAP - PORT_WIDTH;
        int portX = this.formX + this.formWidth - PORT_WIDTH;

        this.domainField = new EditBox(this.font, this.formX + SCHEME_WIDTH, this.formY + 12,
            domainWidth, 20, label("domain"));
        this.domainField.setMaxLength(512);
        this.domainField.setHint(Component.literal("relay.example.com"));
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

        EditBox playerName = new EditBox(this.font, this.formX, this.formY + 56, this.formWidth, 20, label("player_name"));
        playerName.setMaxLength(64);
        playerName.setValue(client.getUser().getName());
        playerName.setEditable(false);
        playerName.setTextColorUneditable(SECONDARY_COLOR);
        playerName.active = false;
        // 仅绘制玩家名，不加入交互控件列表，禁止点击和键盘聚焦。
        this.addRenderableOnly(playerName);

        this.tokenField = new EditBox(this.font, this.formX, this.formY + 100,
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
        }).bounds(this.formX + this.formWidth - 50, this.formY + 100, 50, 20).build());

        int buttonWidth = (this.formWidth - GAP) / 2;
        this.saveButton = this.addRenderableWidget(Button.builder(label("save"), button -> this.saveSettings(client))
            .bounds(this.formX, this.height - 28, buttonWidth, 20).build());
        this.cancelButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
            .bounds(this.formX + buttonWidth + GAP, this.height - 28, this.formWidth - buttonWidth - GAP, 20).build());
        this.updateControls();
        if (!this.loadStarted) {
            this.loadSettings(client);
        }
    }

    private static Component label(String key) {
        return Component.translatable(PREFIX + key);
    }

    private void inputChanged() {
        this.ioError = null;
        this.updateControls();
    }

    private void updateControls() {
        boolean busy = this.loading || this.saving;
        this.domainField.setEditable(!busy);
        this.portField.setEditable(!busy);
        this.tokenField.setEditable(!busy);
        this.visibilityButton.active = !busy;
        this.cancelButton.active = !this.saving;
        String validationError = null;
        try {
            ClientConnectionSettings.fromInput(this.domainDraft, this.portDraft, this.tokenDraft);
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
                this.domainField.setValue(settings.serverDomain());
                this.portField.setValue(Integer.toString(settings.serverPort()));
                this.tokenField.setValue(settings.token());
            } else {
                this.ioError = "error.load_failed";
            }
            this.updateControls();
            if (client.screen == this) {
                this.setInitialFocus(this.domainField);
            }
        }));
    }

    private void saveSettings(@NotNull Minecraft client) {
        if (!this.saveButton.active) {
            return;
        }
        var settings = ClientConnectionSettings.fromInput(this.domainDraft, this.portDraft, this.tokenDraft);
        Path file = this.settingsFile;
        this.saving = true;
        this.ioError = null;
        this.updateControls();
        CompletableFuture.runAsync(() -> {
            try {
                settings.save(file);
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
                this.ioError = "error.save_failed";
                this.updateControls();
            }
        }));
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
        graphics.drawString(this.font, label("domain"), this.formX, this.formY, TEXT_COLOR);
        graphics.drawString(this.font, label("port"), this.formX + this.formWidth - PORT_WIDTH, this.formY, TEXT_COLOR);
        graphics.fill(this.formX, this.formY + 12, this.formX + SCHEME_WIDTH, this.formY + 32, 0xFF202020);
        graphics.renderOutline(this.formX, this.formY + 12, SCHEME_WIDTH, 20, 0xFF555555);
        graphics.drawString(this.font, "https://", this.formX + 5, this.formY + 18, SECONDARY_COLOR);
        graphics.drawString(this.font, label("player_name"), this.formX, this.formY + 44, TEXT_COLOR);
        graphics.drawString(this.font, label("token"), this.formX, this.formY + 88, TEXT_COLOR);
        int y = this.formY + 132;
        int color = this.loading || this.saving ? SECONDARY_COLOR : ERROR_COLOR;
        for (var line : this.font.split(this.status, this.formWidth)) {
            graphics.drawString(this.font, line, this.formX, y, color);
            y += this.font.lineHeight + 1;
        }
    }

}
