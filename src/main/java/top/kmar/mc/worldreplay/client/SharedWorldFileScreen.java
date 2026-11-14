package top.kmar.mc.worldreplay.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import top.kmar.mc.worldreplay.WorldRelay;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 导入/导出存档；文件对话框、压缩和校验均在后台，控件回调回到客户端线程。 */
final class SharedWorldFileScreen extends Screen {

    private static final String PREFIX = "worldrelay.local_world.";
    private static final Object DIALOG_LOCK = new Object();
    private final Screen parent;
    private final Component world;
    private final boolean exporting;
    private final BiFunction<Path, Boolean, CompletableFuture<Void>> operation;
    private String draft = "";
    private Component status = CommonComponents.EMPTY;
    private boolean busy;
    private EditBox input;
    private Button zipButton, folderButton, actionButton, backButton;

    SharedWorldFileScreen(Screen parent, Component world, Function<Path, CompletableFuture<Void>> importer) {
        this(parent, world, false, (path, replace) -> importer.apply(path));
    }

    static SharedWorldFileScreen forExport(Screen parent, Component world, BiFunction<Path, Boolean, CompletableFuture<Void>> exporter) {
        var screen = new SharedWorldFileScreen(parent, world, true, exporter);
        String name = world.getString().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (name.length() > 64) name = name.substring(0, 64);
        screen.draft = Path.of(System.getProperty("user.home", ""), "WorldRelay-" + name + ".zip").toString();
        return screen;
    }

    private SharedWorldFileScreen(Screen parent, Component world, boolean exporting, BiFunction<Path, Boolean, CompletableFuture<Void>> operation) {
        super(Component.translatable(PREFIX + (exporting ? "export" : "import")));
        this.parent = parent;
        this.world = world;
        this.exporting = exporting;
        this.operation = operation;
    }

    @Override
    protected void init() {
        int width = Math.min(380, this.width - 32);
        int x = (this.width - width) / 2;
        int y = Math.max(90, this.height / 2 - 10);
        input = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.translatable(PREFIX + (exporting ? "export_path" : "path"))));
        input.setMaxLength(4096);
        input.setValue(draft);
        input.setResponder(value -> {
            draft = value;
            if (!busy) status = CommonComponents.EMPTY;
        });
        zipButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + (exporting ? "choose_export" : "choose_zip")), button -> choose(false))
            .bounds(x, y + 28, exporting ? width : (width - 6) / 2, 20).build());
        if (!exporting) folderButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "choose_folder"), button -> choose(true))
            .bounds(x + (width + 6) / 2, y + 28, (width - 6) / 2, 20).build());
        actionButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + (exporting ? "export" : "import")), button -> submit())
            .bounds(this.width / 2 - 155, this.height - 30, 150, 20).build());
        backButton = addRenderableWidget(Button.builder(exporting ? CommonComponents.GUI_BACK : CommonComponents.GUI_CANCEL, button -> onClose())
            .bounds(this.width / 2 + 5, this.height - 30, 150, 20).build());
        setInitialFocus(input);
        controls();
    }

    private void controls() {
        input.setEditable(!busy);
        zipButton.active = actionButton.active = backButton.active = !busy;
        if (folderButton != null) folderButton.active = !busy;
    }

    private void choose(boolean folder) {
        if (busy || minecraft == null) return;
        busy = true;
        controls();
        String defaultPath = draft.isBlank() ? System.getProperty("user.home", "") : draft;
        String caption = Component.translatable(PREFIX + (exporting ? "choose_export" : folder ? "choose_folder" : "choose_zip")).getString();
        var client = minecraft;
        CompletableFuture.supplyAsync(() -> {
            synchronized (DIALOG_LOCK) {
                if (folder) return TinyFileDialogs.tinyfd_selectFolderDialog(caption, defaultPath);
                try (var stack = MemoryStack.stackPush()) {
                    var filter = stack.mallocPointer(1).put(0, stack.UTF8("*.zip"));
                    if (exporting) return TinyFileDialogs.tinyfd_saveFileDialog(caption, defaultPath, filter, "ZIP");
                    return TinyFileDialogs.tinyfd_openFileDialog(caption, defaultPath, filter, "ZIP", false);
                }
            }
        }, Util.ioPool()).whenComplete((selected, failure) -> client.execute(() -> {
            busy = false;
            if (selected != null) {
                draft = selected;
                input.setValue(draft);
            }
            if (failure != null) status = Component.translatable(PREFIX + (exporting ? "export_dialog_failed" : "dialog_failed"));
            controls();
        }));
    }

    private void submit() {
        if (busy || minecraft == null) return;
        Path source;
        try {
            if (draft.isBlank()) throw new IllegalArgumentException();
            if (exporting && !draft.toLowerCase(Locale.ROOT).endsWith(".zip")) draft += ".zip";
            source = Path.of(draft).toAbsolutePath().normalize();
            input.setValue(draft);
        } catch (RuntimeException failure) {
            status = Component.translatable(PREFIX + (exporting ? "export_invalid_path" : "invalid_path"));
            return;
        }
        perform(source, false);
    }

    private void perform(Path path, boolean replace) {
        busy = true;
        status = Component.translatable(PREFIX + (exporting ? "exporting" : "importing"));
        controls();
        var client = minecraft;
        if (client == null) return;
        operation.apply(path, replace).whenComplete((ignored, failure) -> client.execute(() -> {
            busy = false;
            if (failure == null) {
                if (!exporting) client.setScreen(parent);
                else {
                    status = Component.translatable(PREFIX + "export_success");
                    controls();
                }
            } else {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (exporting && cause instanceof FileAlreadyExistsException) {
                    status = CommonComponents.EMPTY;
                    controls();
                    client.setScreen(new ConfirmScreen(confirmed -> {
                        client.setScreen(this);
                        if (confirmed) perform(path, true);
                    }, Component.translatable(PREFIX + "export_replace_title"), Component.translatable(PREFIX + "export_replace_body", path.toString())));
                    return;
                }
                WorldRelay.LOGGER.warn(exporting ? "Local world export failed" : "Local world cache import failed; previous local data is retained", failure);
                status = Component.translatable(PREFIX + (exporting ? "export_failed" : "import_failed"));
                controls();
            }
        }));
    }

    @Override
    public void onFilesDrop(@NotNull List<Path> paths) {
        if (!exporting && !busy && paths.size() == 1) {
            draft = paths.getFirst().toString();
            input.setValue(draft);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.drawCenteredString(font, world, width / 2, 35, 0xFFAAAAAA);
        int y = 52;
        for (var line : font.split(Component.translatable(PREFIX + (exporting ? "export_help" : "import_help")), Math.min(380, width - 32))) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFAAAAAA);
            y += font.lineHeight + 2;
        }
        y = input.getY() + 56;
        for (var line : font.split(status, Math.min(380, width - 32))) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFCC66);
            y += font.lineHeight + 2;
        }
    }

    @Override public boolean shouldCloseOnEsc() { return !busy; }
    @Override public void onClose() { if (!busy && minecraft != null) minecraft.setScreen(parent); }
}
