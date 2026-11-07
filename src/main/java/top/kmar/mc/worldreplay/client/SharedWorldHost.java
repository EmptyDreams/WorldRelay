package top.kmar.mc.worldreplay.client;

import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.worldrelay.tunnel.TunnelConnector;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 控制通道失联时只重新登记当前任期；不打开另一份世界，也不释放所有权。 */
final class SharedWorldHost implements AutoCloseable {

    private final TunnelConnector connector = new TunnelConnector();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "worldrelay-host-tunnel");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile TunnelConnector.Session control;
    private volatile int players;
    private boolean warned;
    private volatile boolean republish;

    @SuppressWarnings("resource")
        // 准入记录交给 Session，在本机连接关闭时清理。
    SharedWorldHost(ClientConnectionSettings settings, String user, LocalWorldSession owner, String compatibility, int port, Runnable fenced) {
        retries.scheduleWithFixedDelay(() -> {
            if (closed.get() || control != null && control.isOpen() && !republish) return;
            try {
                // 验证内置服务器已实际监听；不把尚未可连接的世界登记为 RUNNING。
                await(connector.local(port)).close();
                var current = control;
                if (current != null && current.isOpen()) {
                    TunnelApi.ready(settings, user, owner, current.registrationId());
                    republish = false;
                    warned = false;
                    return;
                }
                TunnelApi.Ticket ticket = TunnelApi.ticket(settings, user, owner.worldId(), owner, compatibility, false);
                var connected = await(connector.connect(ticket.endpoint(), ticket.token(), request -> {
                    if (closed.get()) return;
                    String[] fields = request.split("\n", -1);
                    if (fields.length != 3 || !fields[0].matches("[A-Za-z0-9_-]{43}") || !(fields[2].equals("0") || fields[2].equals("1"))) return;
                    connector.local(port).thenCompose(local -> connector.connect(ticket.endpoint(), fields[0], null)
                        .whenComplete((stream, error) -> {
                            if (error != null) {
                                try {
                                    local.close();
                                } catch (java.io.IOException ignored) { /* 清理未配对的本机连接。 */ }
                            } else {
                                AutoCloseable admission = top.kmar.mc.worldreplay.playerdata.TunnelPlayerAccess.register(local.getLocalSocketAddress(), fields[1], fields[2].equals("1"));
                                stream.bindRaw(local, () -> {
                                    try {
                                        admission.close();
                                    } catch (Exception failure) {
                                        WorldRelay.LOGGER.warn("Unable to remove tunnel admission");
                                    }
                                });
                            }
                        }));
                }));
                if (closed.get()) {
                    connected.close();
                    return;
                }
                control = connected;
                try {
                    TunnelApi.ready(settings, user, owner, connected.registrationId());
                } catch (Exception failure) {
                    connected.close();
                    throw failure;
                }
                warned = false;
                republish = false;
            } catch (WorldOwnershipClient.RequestException failure) {
                if (Set.of("STALE_EPOCH", "OWNER_MISMATCH", "SESSION_MISMATCH").contains(failure.code())) {
                    close();
                    fenced.run();
                } else unavailable(failure.code());
            } catch (Exception failure) {
                if (!closed.get()) unavailable(failure.getClass().getSimpleName());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void unavailable(String reason) {
        if (warned) return;
        warned = true;
        WorldRelay.LOGGER.warn("Shared world tunnel is unavailable; ownership is retained ({})", reason);
        var client = net.minecraft.client.Minecraft.getInstance();
        client.execute(() -> {
            if (!closed.get() && client.player != null)
                client.gui.getChat().addMessage(net.minecraft.network.chat.Component.translatable("worldrelay.creation.tunnel_unavailable"));
        });
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            throw failure;
        }
    }

    void players(int count) {
        players = count;
    }

    void republish() {
        republish = true;
    }

    int players() {
        return players;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            retries.shutdownNow();
            connector.close();
        }
    }

}