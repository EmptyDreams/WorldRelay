package top.kmar.mc.worldreplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.mc.worldreplay.playerdata.MissingPlayerDataConfirmation;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;

/** 原版界面保留全部世界选项；IO 队列串行处理会话、协调请求和心跳。 */
@EventBusSubscriber(modid = WorldRelay.MODID, value = Dist.CLIENT)
public final class SharedWorldCreation {
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "worldrelay-creation-io");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Creation active;
    // 仅在客户端主线程调用 openFresh 的期间有效，不替换 Minecraft 的全局 levelSource。
    private static Creation opening;
    private static boolean joining;

    private SharedWorldCreation() { }

    public static Creation opening() { return opening; }

    public static void join(Screen parent, String worldId, String worldName, String status) {
        Minecraft client = Minecraft.getInstance();
        if (joining) return;
        if (!status.equals("FREE") && !status.equals("RECOVERY_REQUIRED") && !status.equals("OWNED_OFFLINE")
            && !status.equals("PREPARING") && !status.equals("STARTING")) {
            show(parent, "not_available", false, null);
            return;
        }
        if (active != null && !active.session.worldId().equals(worldId)) {
            show(parent, "unfinished", false, null);
            return;
        }
        joining = true;
        show(parent, "acquiring", true, null);
        String user = client.getUser().getName();
        UUID profile = client.getUser().getProfileId();
        Path game = client.gameDirectory.toPath();
        IO.execute(() -> {
            try {
                LocalWorldSessionStore.lockGame(game);
                var settings = ClientConnectionSettings.load(game.resolve("config/worldrelay-client.json"));
                var store = new LocalWorldSessionStore(game, settings.serverUri().toString(), user, profile, worldId);
                var session = store.read();
                if (session == null || session.stage() == LocalWorldSession.Stage.CANCELLED) {
                    if (!status.equals("FREE")) throw new IOException("World belongs to another session");
                    session = new LocalWorldSession(worldId, LocalWorldSessionStore.device(game), UUID.randomUUID().toString(),
                        null, 0, LocalWorldSession.Stage.ACQUIRING, null);
                    store.write(session); // 响应丢失时用同一个 requestId 恢复，绝不重新抢锁。
                }
                if (session.stage() != LocalWorldSession.Stage.ACQUIRING && !session.canCancel()) {
                    client.execute(() -> { joining = false; show(parent, "unfinished", false, null); });
                    return;
                }
                if (session.stage() == LocalWorldSession.Stage.CANCELLING) {
                    // 上次取消可能已在服务端成功；直接重试取消，不能先 acquire 已撤销的请求。
                    var creation = new Creation(parent, user, profile, settings, store, session);
                    if (active != null) active.stopHeartbeat();
                    active = creation;
                    client.execute(() -> { joining = false; creation.cancel(); });
                    return;
                }
                var view = WorldOwnershipClient.acquire(settings, user, session);
                if (!view.phase().equals("PREPARING")) {
                    client.execute(() -> { joining = false; show(parent, "unfinished", false, null); });
                    return;
                }
                session = session.owned(view);
                store.write(session);
                var creation = new Creation(parent, user, profile, settings, store, session);
                Creation previous = active;
                if (previous != null) previous.stopHeartbeat();
                active = creation;
                creation.startHeartbeat(view.heartbeatIntervalSeconds());
                if (view.hasSave()) {
                    client.execute(() -> { joining = false; show(parent, "existing_save", false, null); });
                } else {
                    creation.storage = LevelStorageSource.createDefault(store.saves(session.epoch()));
                    client.execute(() -> {
                        joining = false;
                        try {
                            opening = creation;
                            CreateWorldScreen.openFresh(client, new ReturnScreen(creation));
                            if (client.screen instanceof CreateWorldScreen screen) screen.getUiState().setName(worldName);
                        } catch (RuntimeException failure) {
                            show(parent, "failed", false, () -> join(parent, worldId, worldName, status));
                        } finally { opening = null; }
                    });
                }
            } catch (Exception failure) {
                WorldRelay.LOGGER.warn("Shared world acquisition did not complete ({})", failure.getClass().getSimpleName());
                client.execute(() -> {
                    joining = false;
                    show(parent, "failed", false, () -> join(parent, worldId, worldName, status));
                });
            }
        });
    }

    private static void show(Screen parent, String message, boolean pending, Runnable retry) {
        Minecraft.getInstance().setScreen(new SharedWorldOperationScreen(parent, message, pending, retry));
    }

    public static final class Creation {
        private final Screen parent;
        private final String user;
        private final UUID profile;
        private final ClientConnectionSettings settings;
        private final LocalWorldSessionStore store;
        // 仅由单线程 IO 队列更新，volatile 将最新状态发布给客户端主线程。
        private volatile LocalWorldSession session;
        private LevelStorageSource storage;
        private ScheduledFuture<?> heartbeat;
        private boolean busy;
        private boolean resuming;
        private volatile Path worldDirectory;

        private Creation(Screen parent, String user, UUID profile, ClientConnectionSettings settings,
                         LocalWorldSessionStore store, LocalWorldSession session) {
            this.parent = parent;
            this.user = user;
            this.profile = profile;
            this.settings = settings;
            this.store = store;
            this.session = session;
        }

        public LevelStorageSource storage() { return storage; }
        public boolean resuming() { return resuming; }

        private void save(LocalWorldSession next) throws IOException {
            // 即使写盘结果不确定，当前进程也不能再走可取消分支。
            session = next;
            store.write(next);
        }

        private void startHeartbeat(int interval) {
            heartbeat = IO.scheduleWithFixedDelay(() -> {
                if (active != this) return;
                try { WorldOwnershipClient.command(settings, user, session, "heartbeat", null); }
                catch (IOException failure) {
                    WorldRelay.LOGGER.warn("Shared world heartbeat failed; ownership is retained");
                }
            }, interval, interval, TimeUnit.SECONDS);
        }

        private void stopHeartbeat() { if (heartbeat != null) heartbeat.cancel(false); }

        /** 在原版最终确认世界生成之后调用；网络请求完成前不让原版创建世界目录。 */
        public void begin(String folder, Runnable vanillaCreate) {
            if (busy) return;
            if (!session.canOpenCreation() && session.stage() != LocalWorldSession.Stage.START_REQUESTED) {
                show(parent, "unfinished", false, null);
                return;
            }
            busy = true;
            show(parent, "starting", true, null);
            IO.execute(() -> {
                try {
                    save(session.with(LocalWorldSession.Stage.START_REQUESTED, folder));
                    var view = WorldOwnershipClient.command(settings, user, session, "ready", "SAVE_SYNCED");
                    if (view.hasSave() || !view.phase().equals("STARTING")) throw new IOException("Unexpected start state");
                    Path target = storage.getBaseDir().resolve(folder).normalize();
                    if (!target.getParent().equals(storage.getBaseDir())) throw new IOException("Invalid world path");
                    // CREATE_DIRECTORY 而非复用：部分生成或恢复副本绝不能被新建覆盖。
                    Files.createDirectory(target);
                    worldDirectory = target;
                    LocalWorldSessionStore.atomicWrite(target.resolve(SharedPlayerData.MARKER_FILE), "");
                    new SharedPlayerData(target).requestCreation(profile,
                        request -> CompletableFuture.completedFuture(MissingPlayerDataConfirmation.Decision.CREATE))
                        .toCompletableFuture().get();
                    save(session.with(LocalWorldSession.Stage.STARTED, folder));
                    Minecraft.getInstance().execute(() -> {
                        resuming = true;
                        try { vanillaCreate.run(); }
                        catch (RuntimeException failure) { show(parent, "unfinished", false, null); }
                        finally { resuming = false; busy = false; }
                    });
                } catch (Exception failure) {
                    WorldRelay.LOGGER.warn("Shared world start did not complete; local data and ownership are retained");
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        show(parent, "start_failed", false, worldDirectory == null ? () -> begin(folder, vanillaCreate) : null);
                    });
                }
            });
        }

        private void cancel() {
            if (busy) return;
            if (!session.canCancel()) { show(parent, "unfinished", false, null); return; }
            busy = true;
            show(parent, "cancelling", true, null);
            IO.execute(() -> {
                try {
                    save(session.with(LocalWorldSession.Stage.CANCELLING, null));
                    WorldOwnershipClient.cancelCreation(settings, user, session);
                    save(session.with(LocalWorldSession.Stage.CANCELLED, null));
                    stopHeartbeat();
                    active = null;
                    Minecraft.getInstance().execute(() -> { busy = false; Minecraft.getInstance().setScreen(parent); });
                } catch (IOException failure) {
                    // 所有 session 写操作都在同一个 IO 线程上，不存在并发的读改写。
                    //noinspection NonAtomicOperationOnVolatileField
                    session = session.with(LocalWorldSession.Stage.CANCELLING, null);
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        show(parent, "cancel_failed", false, this::cancel);
                    });
                }
            });
        }
    }

    /** 只有离开原版创建主界面才到这里；设置子界面的返回不触发取消。 */
    private static final class ReturnScreen extends Screen {
        private final Creation creation;
        ReturnScreen(Creation creation) {
            super(net.minecraft.network.chat.Component.translatable("worldrelay.menu.shared_worlds"));
            this.creation = creation;
        }
        // 等原版 popScreen 完成临时数据包清理后再切屏，避免 added 中重入 setScreen。
        @Override public void tick() { creation.cancel(); }
        @Override public boolean shouldCloseOnEsc() { return false; }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Creation creation = active;
        if (creation == null || creation.worldDirectory == null
            || !event.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(creation.worldDirectory)) return;
        IO.execute(() -> {
            try {
                creation.save(creation.session.with(LocalWorldSession.Stage.STOPPING, creation.session.folder()));
                WorldOwnershipClient.command(creation.settings, creation.user, creation.session, "stop", "BEGIN");
            } catch (IOException failure) {
                WorldRelay.LOGGER.warn("Shared world stop notification failed; ownership is retained");
            } finally { creation.stopHeartbeat(); }
        });
    }
}
