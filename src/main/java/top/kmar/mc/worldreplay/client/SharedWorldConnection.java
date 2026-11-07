package top.kmar.mc.worldreplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import top.kmar.worldrelay.tunnel.TunnelConnector;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 将固定逻辑地址映射到本次访客连接的本机入口；待连接资源不受旧世界断开事件影响。 */
public final class SharedWorldConnection implements AutoCloseable {

    private static volatile SharedWorldConnection pending;
    private static volatile SharedWorldConnection active;
    private static final java.util.Set<String> CREATION_CONSENTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final TunnelConnector connector = new TunnelConnector();
    private final String host;
    private final String name;
    private final Screen parent;
    private InetSocketAddress local;
    private Screen connectingScreen;
    private Connection minecraftConnection;
    private String consentKey;

    private SharedWorldConnection(Screen parent, String world, String name) {
        this.parent = parent;
        this.name = name;
        if (!UUID.fromString(world).toString().equals(world)) throw new IllegalArgumentException("Invalid world ID");
        host = world + ".localhost";
    }

    /** 在 IO 线程预先完成中心配对，再让原版连接本机入口。 */
    static SharedWorldConnection prepare(Screen parent, ClientConnectionSettings settings, String user, String world, String name, String compatibility) throws Exception {
        var connection = new SharedWorldConnection(parent, world, name);
        try {
            connection.consentKey = settings.serverUri() + "/" + world + "/" + Minecraft.getInstance().getUser().getProfileId();
            var ticket = TunnelApi.ticket(settings, user, world, null, compatibility, CREATION_CONSENTS.contains(connection.consentKey));
            var stream = connection.connector.connect(ticket.endpoint(), ticket.token(), null).get(10, TimeUnit.SECONDS);
            connection.local = connection.connector.listen(stream).get(10, TimeUnit.SECONDS);
            return connection;
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    void start() {
        if (pending != null) pending.close();
        pending = this;
        Minecraft client = Minecraft.getInstance();
        try {
            ConnectScreen.startConnecting(parent, client, new ServerAddress(host, 25565), new ServerData(name, host, ServerData.Type.OTHER), false, null);
            connectingScreen = client.screen;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public static boolean managedHost(String host) {
        return host.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.localhost");
    }

    public static Optional<ResolvedServerAddress> resolve(ServerAddress address) {
        SharedWorldConnection connection = pending != null && pending.host.equals(address.getHost()) ? pending : active;
        if (connection == null || !connection.host.equals(address.getHost()) || address.getPort() != 25565 || connection.local == null)
            return Optional.empty();
        try {
            return Optional.of(ResolvedServerAddress.from(new InetSocketAddress(InetAddress.getByAddress(connection.host,
                new byte[]{127, 0, 0, 1}), connection.local.getPort())));
        } catch (java.net.UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static void joined(Connection connection) {
        var candidate = pending;
        var server = Minecraft.getInstance().getCurrentServer();
        if (candidate != null && server != null && candidate.host.equals(server.ip)) {
            if (active != null && active != candidate) active.close();
            candidate.minecraftConnection = connection;
            active = candidate;
            pending = null;
        }
    }

    public static void left(Connection connection) {
        var current = active;
        if (current != null && current.minecraftConnection == connection) current.close();
    }

    public static void tick() {
        var current = pending;
        Minecraft client = Minecraft.getInstance();
        if (current != null && client.screen != current.connectingScreen
            && (client.screen == current.parent || client.screen instanceof DisconnectedScreen)) current.close();
    }

    public static void disconnected(String host, Component reason) {
        var current = pending;
        if (current == null || !current.host.equals(host) || CREATION_CONSENTS.contains(current.consentKey)
            || !(reason.getContents() instanceof TranslatableContents text)
            || !text.getKey().equals("worldrelay.disconnect.player_data_confirmation_required")) return;
        current.close();
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                CREATION_CONSENTS.add(current.consentKey);
                SharedWorldCreation.join(current.parent, current.host.substring(0, 36), current.name);
            } else client.setScreen(current.parent);
        }, Component.translatable("worldrelay.creation.player_title"), Component.translatable("worldrelay.creation.player_message")));
    }

    @Override
    public void close() {
        if (pending == this) pending = null;
        if (active == this) active = null;
        connector.close();
    }

}