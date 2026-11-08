package top.kmar.mc.worldreplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DirectoryLock;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
    // rsync 或摘要校验可能持续很久，心跳独立运行，且从不修改本地会话文件。
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "worldrelay-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Creation active;
    // 仅在客户端主线程调用 openFresh 的期间有效，不替换 Minecraft 的全局 levelSource。
    private static Creation opening;
    private static boolean joining;

    private SharedWorldCreation() {
    }

    public static Creation opening() {
        return opening;
    }

    public static void join(Screen parent, String worldId, String worldName) {
        Minecraft client = Minecraft.getInstance();
        if (joining) return;
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
            ClientConnectionSettings settings = null;
            LocalWorldSessionStore store = null;
            LocalWorldSession session = null;
            try {
                settings = ClientConnectionSettings.load(game.resolve("config/worldrelay-client.json"));
                var current = SharedWorldListClient.fetch(settings, user).stream().filter(world -> world.worldId().equals(worldId)).findFirst()
                    .orElseThrow(() -> new IOException("World is unavailable"));
                LocalWorldSessionStore.lockGame(game);
                store = new LocalWorldSessionStore(game, settings.serverUri().toString(), user, profile, worldId);
                session = store.read();
                boolean ownedSession = session != null && session.sessionId() != null
                    && session.stage() != LocalWorldSession.Stage.CANCELLED && session.stage() != LocalWorldSession.Stage.COMMITTED;
                if (ownedSession && session.stage() != LocalWorldSession.Stage.UPLOADING && session.stage() != LocalWorldSession.Stage.CANCELLING) {
                    // 旧获取请求的去重记录可能已淘汰；先检查持有关系，不能用旧请求重新获得所有权。
                    WorldOwnershipClient.command(settings, user, session, "heartbeat", null);
                }
                if (current.status().equals("RUNNING") && !ownedSession) {
                    joinGuest(parent, settings, user, worldId, worldName);
                    return;
                }
                if (session == null || session.stage() == LocalWorldSession.Stage.CANCELLED || session.stage() == LocalWorldSession.Stage.COMMITTED) {
                    if (!current.status().equals("FREE")) {
                        client.execute(() -> {
                            joining = false;
                            show(parent, "not_available", false, () -> join(parent, worldId, worldName));
                        });
                        return;
                    }
                    session = new LocalWorldSession(worldId, LocalWorldSessionStore.device(game), UUID.randomUUID().toString(),
                        null, 0, LocalWorldSession.Stage.ACQUIRING, null);
                    store.write(session); // 响应丢失时用同一个 requestId 恢复，绝不重新抢锁。
                }
                if (session.stage() == LocalWorldSession.Stage.STOPPED || session.stage() == LocalWorldSession.Stage.UPLOADING) {
                    var creation = new Creation(parent, user, profile, settings, store, session);
                    if (active != null) active.stopHeartbeat();
                    active = creation;
                    creation.worldDirectory = store.saves(session.epoch()).resolve(session.folder());
                    creation.startHeartbeat(10);
                    client.execute(() -> {
                        joining = false;
                        creation.uploadStopped();
                    });
                    return;
                }
                if (session.canResume()) {
                    client.execute(() -> show(parent, "recovering", true, null));
                    try {
                        var prepared = SharedWorldRecovery.prepare(settings, user, session, store);
                        var creation = new Creation(parent, user, profile, settings, store, session);
                        creation.worldDirectory = prepared.directory();
                        creation.storage = LevelStorageSource.createDefault(store.saves(session.epoch()));
                        creation.confirmPlayerData();
                        creation.save(session.with(LocalWorldSession.Stage.STARTED, session.folder()));
                        if (active != null) {
                            active.stopHeartbeat();
                            if (active.tunnelHost != null) active.tunnelHost.close();
                        }
                        active = creation;
                        creation.startHeartbeat(prepared.heartbeatIntervalSeconds());
                        client.execute(() -> {
                            joining = false;
                            creation.openExisting();
                        });
                    } catch (Exception failure) {
                        WorldRelay.LOGGER.warn("Shared world recovery did not complete; local files are retained", failure);
                        if (SharedWorldRecovery.ownershipLost(failure)) {
                            offerReplacement(parent, settings, user, store, session);
                            return;
                        }
                        client.execute(() -> {
                            joining = false;
                            show(parent, "recovery_failed", false, () -> join(parent, worldId, worldName));
                        });
                    }
                    return;
                }
                if (session.stage() != LocalWorldSession.Stage.ACQUIRING && session.stage() != LocalWorldSession.Stage.PREPARING
                    && session.stage() != LocalWorldSession.Stage.DOWNLOADING && !session.canCancel()) {
                    client.execute(() -> {
                        joining = false;
                        show(parent, "unfinished", false, null);
                    });
                    return;
                }
                if (session.stage() == LocalWorldSession.Stage.CANCELLING) {
                    // 上次取消可能已在服务端成功；直接重试取消，不能先 acquire 已撤销的请求。
                    var creation = new Creation(parent, user, profile, settings, store, session);
                    if (active != null) active.stopHeartbeat();
                    active = creation;
                    client.execute(() -> {
                        joining = false;
                        creation.cancel();
                    });
                    return;
                }
                WorldOwnershipClient.View view;
                try {
                    view = WorldOwnershipClient.acquire(settings, user, session);
                } catch (WorldOwnershipClient.RequestException failure) {
                    if (failure.status() == 409 && session.stage() == LocalWorldSession.Stage.ACQUIRING) {
                        var changed = SharedWorldListClient.fetch(settings, user).stream().filter(world -> world.worldId().equals(worldId)).findFirst();
                        if (changed.isPresent() && changed.get().status().equals("RUNNING")) {
                            joinGuest(parent, settings, user, worldId, worldName);
                            return;
                        }
                    }
                    throw failure;
                }
                if (!view.phase().equals("PREPARING") && !((session.stage() == LocalWorldSession.Stage.DOWNLOADING || session.localSource() != null)
                    && view.phase().equals("STARTING"))) {
                    client.execute(() -> {
                        joining = false;
                        show(parent, "unfinished", false, null);
                    });
                    return;
                }
                if (session.stage() != LocalWorldSession.Stage.DOWNLOADING) session = LocalWorldReplacement.acceptOwnership(session, view);
                else view.checkSession(session);
                store.write(session);
                var creation = new Creation(parent, user, profile, settings, store, session);
                Creation previous = active;
                if (previous != null) previous.stopHeartbeat();
                active = creation;
                creation.startHeartbeat(view.heartbeatIntervalSeconds());
                if (session.localSource() != null) {
                    client.execute(() -> {
                        joining = false;
                        creation.reuseLocalAndOpen();
                    });
                } else if (view.hasSave()) {
                    client.execute(() -> {
                        joining = false;
                        creation.downloadAndOpen();
                    });
                } else {
                    creation.storage = LevelStorageSource.createDefault(store.saves(session.epoch()));
                    client.execute(() -> {
                        joining = false;
                        try {
                            opening = creation;
                            CreateWorldScreen.openFresh(client, new ReturnScreen(creation));
                            if (client.screen instanceof CreateWorldScreen screen) screen.getUiState().setName(worldName);
                        } catch (RuntimeException failure) {
                            show(parent, "failed", false, () -> join(parent, worldId, worldName));
                        } finally {
                            opening = null;
                        }
                    });
                }
            } catch (Exception failure) {
                if (settings != null && store != null && session != null && session.localSource() != null
                    && LocalWorldReplacement.reuseDenied(failure)) {
                    offerReplacement(parent, settings, user, store, session);
                    return;
                }
                if (settings != null && store != null && session != null && SharedWorldRecovery.ownershipLost(failure)) {
                    offerReplacement(parent, settings, user, store, session);
                    return;
                }
                WorldRelay.LOGGER.warn("Shared world acquisition did not complete ({})", failure.getClass().getSimpleName());
                client.execute(() -> {
                    joining = false;
                    show(parent, "failed", false, () -> join(parent, worldId, worldName));
                });
            }
        });
    }

    @SuppressWarnings("resource") // 所有权交给连接上下文，在取消、失败或断开事件中关闭。
    private static void joinGuest(Screen parent, ClientConnectionSettings settings, String user, String worldId, String worldName) throws Exception {
        var connection = SharedWorldConnection.prepare(parent, settings, user, worldId, worldName);
        Minecraft.getInstance().execute(() -> {
            joining = false;
            try {
                connection.start();
            } catch (RuntimeException failure) {
                connection.close();
                show(parent, "connect_failed", false, () -> join(parent, worldId, worldName));
            }
        });
    }

    private static void show(Screen parent, String message, boolean pending, Runnable retry) {
        Minecraft.getInstance().setScreen(new SharedWorldOperationScreen(parent, message, pending, retry));
    }

    /** 仅在服务端明确拒绝旧身份之后调用；选择前保留原会话及本地存档。 */
    private static void offerReplacement(
        Screen parent, ClientConnectionSettings settings, String user,
        LocalWorldSessionStore store, LocalWorldSession previous
    ) {
        Creation old = active;
        if (old != null && old.session.worldId().equals(previous.worldId()) && old.session.epoch() == previous.epoch()) {
            old.fenced = true;
            old.stopHeartbeat();
            if (old.tunnelHost != null) old.tunnelHost.close();
            active = null;
        }
        Minecraft client = Minecraft.getInstance();
        try {
            var current = SharedWorldListClient.fetch(settings, user).stream().filter(world -> world.worldId().equals(previous.worldId()))
                .findFirst().orElseThrow(() -> new IOException("World is unavailable"));
            boolean free = current.status().equals("FREE"), running = current.status().equals("RUNNING");
            boolean localAvailable = false;
            if (free) {
                try {
                    localAvailable = LocalWorldReplacement.allowed(settings, user, previous, store);
                } catch (IOException ignored) { /* 本地存档缺失或仍被占用时，只允许选择服务器来源。 */ }
            }
            boolean canUseLocal = localAvailable;
            client.execute(() -> {
                joining = false;
                client.setScreen(new SharedWorldRecoveryScreen(parent, canUseLocal, free || running, running,
                    () -> replaceSession(parent, store, previous, current.name(), true),
                    () -> replaceSession(parent, store, previous, current.name(), false)));
            });
        } catch (IOException failure) {
            client.execute(() -> {
                joining = false;
                show(parent, "failed", false, () -> join(parent, previous.worldId(), ""));
            });
        }
    }

    private static void replaceSession(
        Screen parent, LocalWorldSessionStore store, LocalWorldSession previous,
        String worldName, boolean useLocal
    ) {
        if (joining) return;
        joining = true;
        show(parent, "acquiring", true, null);
        IO.execute(() -> {
            try {
                LocalWorldReplacement.select(store, previous, useLocal);
                Minecraft.getInstance().execute(() -> {
                    joining = false;
                    join(parent, previous.worldId(), worldName);
                });
            } catch (IOException failure) {
                Minecraft.getInstance().execute(() -> {
                    joining = false;
                    show(parent, "recovery_failed", false, () -> join(parent, previous.worldId(), worldName));
                });
            }
        });
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
        private volatile boolean normalStop;
        private volatile boolean saveReturned;
        private volatile boolean finishing;
        private volatile SharedWorldHost tunnelHost;
        private volatile boolean fenced;
        private long nextHostAttempt;
        private long nextPlayerCount;
        private boolean finishScreenShown;
        private String finishMessage = "uploading";
        private boolean finishPending = true;

        private Creation(
            Screen parent, String user, UUID profile, ClientConnectionSettings settings,
            LocalWorldSessionStore store, LocalWorldSession session
        ) {
            this.parent = parent;
            this.user = user;
            this.profile = profile;
            this.settings = settings;
            this.store = store;
            this.session = session;
        }

        public LevelStorageSource storage() {
            return storage;
        }

        public boolean resuming() {
            return resuming;
        }

        private void save(LocalWorldSession next) throws IOException {
            // 即使写盘结果不确定，当前进程也不能再走可取消分支。
            session = next;
            store.write(next);
        }

        private void startHeartbeat(int interval) {
            heartbeat = HEARTBEATS.scheduleWithFixedDelay(() -> {
                if (active != this) return;
                try {
                    SharedWorldHost host = tunnelHost;
                    boolean republish = WorldOwnershipClient.heartbeat(settings, user, session, host == null ? null : host.players());
                    if (republish && host != null) host.republish();
                } catch (IOException failure) {
                    WorldRelay.LOGGER.warn("Shared world heartbeat failed; ownership is retained");
                }
            }, interval, interval, TimeUnit.SECONDS);
        }

        private void stopHeartbeat() {
            if (heartbeat != null) heartbeat.cancel(false);
        }

        private void reuseLocalAndOpen() {
            if (busy) return;
            busy = true;
            show(parent, "recovering", true, null);
            IO.execute(() -> {
                try {
                    var source = session.localSource();
                    save(session.with(LocalWorldSession.Stage.PREPARING, source.folder()));
                    var prepared = LocalWorldReplacement.prepare(settings, user, session, store);
                    worldDirectory = prepared.directory();
                    storage = LevelStorageSource.createDefault(store.saves(session.epoch()));
                    confirmPlayerData();
                    save(session.with(LocalWorldSession.Stage.STARTED, source.folder()));
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        openExisting();
                    });
                } catch (Exception failure) {
                    if (SharedWorldRecovery.ownershipLost(failure)) {
                        offerReplacement(parent, settings, user, store, session);
                        return;
                    }
                    WorldRelay.LOGGER.warn("Shared world local reuse did not complete; local files and recovery choice are retained", failure);
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        show(parent, "recovery_failed", false, () -> join(parent, session.worldId(), ""));
                    });
                }
            });
        }

        private void downloadAndOpen() {
            if (busy) return;
            busy = true;
            show(parent, "downloading", true, null);
            IO.execute(() -> {
                try {
                    String folder = session.stage() == LocalWorldSession.Stage.DOWNLOADING ? session.folder() : "world";
                    save(session.with(LocalWorldSession.Stage.DOWNLOADING, folder));
                    Path game = Minecraft.getInstance().gameDirectory.toPath();
                    worldDirectory = SharedWorldSync.download(game, settings, user, session, store);
                    confirmPlayerData();
                    var view = WorldOwnershipClient.command(settings, user, session, "ready", "SAVE_SYNCED");
                    if (!view.phase().equals("STARTING") || !java.util.Objects.equals(view.baseVersion(), session.baseVersion())) {
                        throw new IOException("Unexpected start state");
                    }
                    storage = LevelStorageSource.createDefault(store.saves(session.epoch()));
                    save(session.with(LocalWorldSession.Stage.STARTED, session.folder()));
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        openExisting();
                    });
                } catch (Exception failure) {
                    if (SharedWorldRecovery.ownershipLost(failure)) {
                        offerReplacement(parent, settings, user, store, session);
                        return;
                    }
                    WorldRelay.LOGGER.warn("Shared world download or start failed; ownership and local files are retained");
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        show(parent, "download_failed", false,
                            session.stage() == LocalWorldSession.Stage.DOWNLOADING ? this::downloadAndOpen : null);
                    });
                }
            });
        }

        private void confirmPlayerData() throws IOException, ExecutionException, InterruptedException {
            var playerData = new SharedPlayerData(worldDirectory);
            var check = playerData.check(profile);
            if (check.status() == SharedPlayerData.Status.ERROR) throw new IOException("Unreadable shared player data");
            if (check.status() == SharedPlayerData.Status.NEEDS_CREATION_CONFIRMATION) {
                CompletableFuture<MissingPlayerDataConfirmation.Decision> decision = new CompletableFuture<>();
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ConfirmScreen(confirmed -> {
                    show(parent, "recovering", true, null);
                    decision.complete(confirmed ? MissingPlayerDataConfirmation.Decision.CREATE : MissingPlayerDataConfirmation.Decision.CANCEL);
                }, Component.translatable("worldrelay.creation.player_title"), Component.translatable("worldrelay.creation.player_message"))));
                if (playerData.requestCreation(profile, request -> decision).toCompletableFuture().get()
                    != MissingPlayerDataConfirmation.Decision.CREATE) throw new IOException("Player creation cancelled");
            }
        }

        private void openExisting() {
            new WorldOpenFlows(Minecraft.getInstance(), storage).openWorld(session.folder(),
                () -> show(parent, "recovery_failed", false, null));
        }

        private void uploadStopped() {
            if (fenced && session.stage() != LocalWorldSession.Stage.UPLOADING) {
                chooseReplacement();
                return;
            }
            if (busy) return;
            busy = true;
            finishing = true;
            finishMessage = "uploading";
            finishPending = true;
            finishScreenShown = false;
            IO.execute(() -> {
                try {
                    Path game = Minecraft.getInstance().gameDirectory.toPath();
                    if (session.stage() == LocalWorldSession.Stage.STOPPED) {
                        // 已停服记录可以在下次客户端启动后重试；崩溃遗留 STARTED/STOPPING 不会走这里。
                        try (DirectoryLock ignored = DirectoryLock.create(worldDirectory)) {
                            save(SharedWorldSync.prepareUpload(session, store));
                        }
                    }
                    // 请求丢失时可重复 BEGIN；在上传前明确撤下房主端点。
                    // UPLOADING 的提交可能早已成功，故先由同步层查询结果，再决定是否上传。
                    SharedWorldSync.upload(game, settings, user, session, store);
                    save(session.with(LocalWorldSession.Stage.COMMITTED, session.folder()));
                    stopHeartbeat();
                    active = null;
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        finishing = false;
                        Minecraft.getInstance().setScreen(parent);
                    });
                } catch (Exception failure) {
                    if (SharedWorldRecovery.ownershipLost(failure)) {
                        offerReplacement(parent, settings, user, store, session);
                        return;
                    }
                    WorldRelay.LOGGER.warn("Shared world upload did not complete; snapshot, ownership and retry identity are retained", failure);
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        finishMessage = "upload_failed";
                        finishPending = false;
                        finishScreenShown = false;
                    });
                }
            });
        }

        private void chooseReplacement() {
            // 已提交但成功响应丢失时先确认回执，不能误把它当作管理员撤销。
            if (session.stage() == LocalWorldSession.Stage.UPLOADING) {
                uploadStopped();
                return;
            }
            finishing = false;
            show(parent, "recovering", true, null);
            IO.execute(() -> offerReplacement(parent, settings, user, store, session));
        }

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
                        try {
                            vanillaCreate.run();
                        } catch (RuntimeException failure) {
                            show(parent, "unfinished", false, null);
                        } finally {
                            resuming = false;
                            busy = false;
                        }
                    });
                } catch (Exception failure) {
                    if (SharedWorldRecovery.ownershipLost(failure)) {
                        offerReplacement(parent, settings, user, store, session);
                        return;
                    }
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
            if (!session.canCancel()) {
                show(parent, "unfinished", false, null);
                return;
            }
            busy = true;
            show(parent, "cancelling", true, null);
            IO.execute(() -> {
                try {
                    save(session.with(LocalWorldSession.Stage.CANCELLING, null));
                    WorldOwnershipClient.cancelCreation(settings, user, session);
                    save(session.with(LocalWorldSession.Stage.CANCELLED, null));
                    stopHeartbeat();
                    active = null;
                    Minecraft.getInstance().execute(() -> {
                        busy = false;
                        Minecraft.getInstance().setScreen(parent);
                    });
                } catch (IOException failure) {
                    if (SharedWorldRecovery.ownershipLost(failure)) {
                        offerReplacement(parent, settings, user, store, session);
                        return;
                    }
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
        @Override
        public void tick() {
            creation.cancel();
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Creation creation = active;
        if (creation == null || creation.worldDirectory == null
            || !event.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(creation.worldDirectory)) return;
        creation.normalStop = true;
        if (creation.tunnelHost != null) creation.tunnelHost.close();
        IO.execute(() -> {
            try {
                creation.save(creation.session.with(LocalWorldSession.Stage.STOPPING, creation.session.folder()));
                WorldOwnershipClient.command(creation.settings, creation.user, creation.session, "stop", "BEGIN");
            } catch (IOException failure) {
                WorldRelay.LOGGER.warn("Shared world stop notification failed; ownership is retained");
            }
        });
    }

    /** Mixin 只报告正常返回；实际复制还要等待服务器线程真正退出。 */
    public static void saved(MinecraftServer server) {
        Creation creation = matching(server);
        if (creation != null) creation.saveReturned = true;
    }

    private static Creation matching(MinecraftServer server) {
        Creation creation = active;
        return creation != null && creation.worldDirectory != null
            && server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(creation.worldDirectory) ? creation : null;
    }

    public static boolean managed(MinecraftServer server) {
        return matching(server) != null;
    }

    /** 仅向可选客户端兼容暴露实际匹配的房主世界身份，不使用遗留恢复记录。 */
    public static @org.jetbrains.annotations.Nullable String worldId(MinecraftServer server) {
        Creation creation = matching(server);
        return creation == null ? null : creation.session.worldId();
    }

    @SubscribeEvent
    public static void onJoined(ClientPlayerNetworkEvent.LoggingIn event) {
        SharedWorldConnection.joined(event.getConnection());
    }

    @SubscribeEvent
    public static void onLeft(ClientPlayerNetworkEvent.LoggingOut event) {
        SharedWorldConnection.left(event.getConnection());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        Creation creation = matching(event.getServer());
        if (creation == null) return;
        creation.finishing = true;
        IO.execute(() -> {
            try {
                // 事件仍在服务器线程中触发；IO 线程等待 onServerExit 与所有 finally 完成。
                event.getServer().getRunningThread().join();
                if (!creation.normalStop || !creation.saveReturned) {
                    throw new IOException("World did not stop cleanly");
                }
                creation.save(creation.session.with(LocalWorldSession.Stage.STOPPED, creation.session.folder()));
                if (creation.fenced) {
                    Minecraft.getInstance().execute(() -> {
                        creation.finishMessage = "ownership_lost";
                        creation.finishPending = false;
                        creation.finishScreenShown = false;
                    });
                    return;
                }
                Minecraft.getInstance().execute(creation::uploadStopped);
            } catch (Exception failure) {
                Minecraft.getInstance().execute(() -> {
                    creation.finishMessage = creation.fenced ? "ownership_lost" : "save_failed";
                    creation.finishPending = false;
                    creation.finishScreenShown = false;
                });
            }
        });
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        SharedWorldConnection.tick();
        Creation creation = active;
        var server = client.getSingleplayerServer();
        if (creation != null && !creation.finishing && !creation.fenced && client.player != null && server != null && matching(server) == creation) {
            long now = net.minecraft.Util.getMillis();
            if (creation.tunnelHost == null && now >= creation.nextHostAttempt) {
                creation.nextHostAttempt = now + 5_000;
                if (server.isPublished() || server.publishServer(null, server.getWorldData().isAllowCommands(), HttpUtil.getAvailablePort())) {
                    creation.tunnelHost = new SharedWorldHost(creation.settings, creation.user, creation.session, server.getPort(),
                        () -> client.execute(() -> {
                            creation.fenced = true;
                            client.gui.getChat().addMessage(Component.translatable("worldrelay.creation.ownership_lost"));
                        }));
                } else WorldRelay.LOGGER.warn("Shared world local listener could not be opened; ownership is retained");
            }
            if (creation.tunnelHost != null && now >= creation.nextPlayerCount) {
                creation.nextPlayerCount = now + 1_000;
                var host = creation.tunnelHost;
                server.execute(() -> host.players(server.getPlayerCount()));
            }
        }
        if (creation == null || !creation.finishing || client.level != null || creation.finishScreenShown) return;
        creation.finishScreenShown = true;
        if (creation.finishMessage.equals("ownership_lost")) {
            creation.chooseReplacement();
            return;
        }
        show(creation.parent, creation.finishMessage, creation.finishPending,
            creation.finishMessage.equals("upload_failed") ? creation::uploadStopped : null);
    }

}
