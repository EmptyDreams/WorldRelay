package top.kmar.worldrelay.server.tunnel;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.protocol.HostEndpoint;
import top.kmar.worldrelay.server.protocol.OwnershipView;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldStore;
import top.kmar.worldrelay.tunnel.TunnelWire;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 鉴权后的世界路由；每条数据连接单独配对，不解释或修改 Minecraft 数据包。 */
@SuppressWarnings("resource") // 处理器借用的 Netty 事件循环统一由 Gateway.close 关闭。
public final class TunnelGateway implements AutoCloseable {

    private enum Role {HOST, JOIN, ATTACH}

    public record Grant(String token, Instant expiresAt) {

        @SuppressWarnings("NullableProblems") // 不依赖 IDE 外部注解；常量非空并且隐去凭据。
        @Override
        public String toString() {
            return "TunnelGrant[redacted]";
        }

    }

    private record Ticket(Role role, String world, WorldStore.Identity owner, String compatibility, Lease lease, Pending pending,
                          String guestUser, boolean consent, long expires) {

    }

    private record Pending(Peer guest, Lease lease) {

    }

    private final class Lease {

        final String world, id = UUID.randomUUID().toString(), compatibility;
        final WorldStore.Identity owner;
        final Peer control;
        final AtomicBoolean active = new AtomicBoolean(true);
        volatile boolean published;
        final Set<Peer> connections = ConcurrentHashMap.newKeySet();

        Lease(String world, WorldStore.Identity owner, String compatibility, Peer control) {
            this.world = world;
            this.owner = owner;
            this.compatibility = compatibility;
            this.control = control;
            connections.add(control);
        }

        boolean current() {
            return active.get() && leases.get(world) == this && control.channel().isActive();
        }

        void close() {
            if (active.compareAndSet(true, false)) connections.forEach(TunnelPeer::close);
        }

    }

    private final WorldStore worlds;
    private final TunnelConfig config;
    private final ConcurrentMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestHostTickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final NioEventLoopGroup acceptor = new NioEventLoopGroup(1, daemon("worldrelay-tunnel-accept"));
    private final NioEventLoopGroup workers = new NioEventLoopGroup(2, daemon("worldrelay-tunnel-io"));
    private final ExecutorService authority = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), daemon("worldrelay-tunnel-auth"), new ThreadPoolExecutor.AbortPolicy());
    private final DefaultChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.function.LongSupplier ticker;
    private Channel listener;
    private TunnelIdentity tls;

    public TunnelGateway(WorldStore worlds, TunnelConfig config) {
        this(worlds, config, System::nanoTime);
    }

    TunnelGateway(WorldStore worlds, TunnelConfig config, java.util.function.LongSupplier ticker) {
        this.worlds = worlds;
        this.config = config;
        this.ticker = ticker;
        worlds.configureTunnels(this::invalidate, world -> {
            Lease lease = leases.get(world);
            return lease != null && lease.published && lease.current();
        });
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public void start() throws IOException {
        try {
            if (config.mode() == TunnelConfig.Mode.TCP) tls = new TunnelIdentity(config.tlsDirectory());
            listener = new ServerBootstrap().group(acceptor, workers).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(131072, 262144))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channels.add(channel);
                        int count = connectionCount.incrementAndGet();
                        channel.closeFuture().addListener(done -> connectionCount.decrementAndGet());
                        if (closed.get() || count > 128) {
                            channel.close();
                            return;
                        }
                        var pipeline = channel.pipeline();
                        boolean ws = config.mode() == TunnelConfig.Mode.WSS;
                        if (tls != null) pipeline.addLast(tls.context.newHandler(channel.alloc()));
                        if (ws) {
                            pipeline.addLast(new HttpServerCodec(), new HttpObjectAggregator(8192));
                            pipeline.addLast(new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                .websocketPath(TunnelWire.PATH).maxFramePayloadLength(TunnelWire.CHUNK + 1)
                                .handshakeTimeoutMillis(10_000).build()));
                            WebSocketFrames.install(pipeline);
                        }
                        TunnelFrames.install(pipeline, ws);
                        pipeline.addLast(new Peer());
                    }
                }).bind(config.bindAddress(), config.port()).syncUninterruptibly().channel();
            channels.add(listener);
        } catch (RuntimeException failure) {
            close();
            throw new IOException("Unable to bind tunnel listener", failure);
        }
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) listener.localAddress();
    }

    public Grant host(String world, WorldStore.Identity owner, String compatibility) throws WorldAccessException {
        checkCompatibility(compatibility);
        synchronized (worlds) {
            worlds.checkTunnelOwner(world, owner);
            Grant grant = issue(new Ticket(Role.HOST, world, owner, compatibility, null, null, null, false, ticker.getAsLong() + TimeUnit.SECONDS.toNanos(60)));
            latestHostTickets.put(world, grant.token());
            return grant;
        }
    }

    public Grant join(String world, String username, String compatibility, boolean consent) throws WorldAccessException {
        top.kmar.worldrelay.server.auth.UserStore.validateUsername(username);
        checkCompatibility(compatibility);
        WorldStore.Identity owner = worlds.joinTunnelOwner(world);
        Lease lease = leases.get(world);
        if (lease == null || !lease.current() || !lease.owner.equals(owner)) throw unavailable();
        if (!lease.compatibility.equals(compatibility)) throw new WorldAccessException(409, "MOD_MISMATCH", "Host and guest mod versions differ");
        return issue(new Ticket(Role.JOIN, world, owner, compatibility, lease, null, username, consent, ticker.getAsLong() + TimeUnit.SECONDS.toNanos(60)));
    }

    public OwnershipView ready(String world, WorldStore.Identity owner, String registration) throws IOException, WorldAccessException {
        synchronized (worlds) {
            Lease lease = leases.get(world);
            if (lease == null || !lease.current() || !lease.owner.equals(owner) || !lease.id.equals(registration)) throw unavailable();
            lease.published = true;
            try {
                return worlds.ready(world, owner, WorldStore.ReadyStage.PORT_OPENED, null, new HostEndpoint(world + ".localhost", 25565));
            } catch (IOException | WorldAccessException | RuntimeException failure) {
                lease.published = false;
                throw failure;
            }
        }
    }

    public void describe(JsonWriter writer, Grant grant) {
        writer.beginObject().name("mode").value(config.mode().name().toLowerCase(java.util.Locale.ROOT))
            .name("ticket").value(grant.token()).name("expiresAt").value(grant.expiresAt().toString());
        if (config.mode() == TunnelConfig.Mode.WSS) writer.name("path").value(TunnelWire.PATH);
        else writer.name("host").value(config.publicHost()).name("port").value(config.publicPort() == 0 ? address().getPort() : config.publicPort())
            .name("fingerprint").value(tls.fingerprint);
        writer.endObject();
    }

    private synchronized Grant issue(Ticket ticket) throws WorldAccessException {
        tickets.values().removeIf(value -> value.expires <= ticker.getAsLong() || value.lease != null && !value.lease.current());
        if (closed.get() || tickets.size() >= 1024) throw new WorldAccessException(503, "TUNNEL_BUSY", "Tunnel is busy");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(token, ticket);
        return new Grant(token, Instant.now().plusSeconds(60));
    }

    private static void checkCompatibility(String compatibility) {
        if (compatibility == null || !compatibility.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid compatibility fingerprint");
    }

    private static WorldAccessException unavailable() {
        return new WorldAccessException(409, "HOST_UNAVAILABLE", "Host tunnel is unavailable");
    }

    private void invalidate(String world) {
        latestHostTickets.remove(world);
        Lease lease = leases.remove(world);
        if (lease != null) lease.close();
    }

    private final class Peer extends TunnelPeer {

        private boolean hello;
        private volatile Lease lease;
        private volatile boolean controller;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> {if (!ready && !controller) close();}, 10, TimeUnit.SECONDS);
        }

        @Override
        protected void receive(byte type, ByteBuf body) {
            if (type != TunnelWire.HELLO || hello) {
                reject();
                return;
            }
            hello = true;
            String text = TunnelFrames.text(body);
            if (!text.matches("1\\n[A-Za-z0-9_-]{43}")) {
                reject();
                return;
            }
            Ticket ticket = tickets.remove(text.substring(2));
            if (ticket == null || ticket.expires <= ticker.getAsLong()) {
                reject();
                return;
            }
            try {
                authority.execute(() -> {
                    try {
                        synchronized (worlds) {
                            worlds.checkTunnelOwner(ticket.world, ticket.owner);
                            if (ticket.role == Role.HOST && !latestHostTickets.remove(ticket.world, text.substring(2))) {
                                channel.eventLoop().execute(this::reject);
                                return;
                            }
                            activate(ticket);
                        }
                    } catch (Exception failure) {
                        channel.eventLoop().execute(this::reject);
                    }
                });
            } catch (RejectedExecutionException failure) {
                reject();
            }
        }

        private void activate(Ticket ticket) throws WorldAccessException {
            if (!channel.isActive() || closed.get()) {
                close();
                return;
            }
            if (ticket.role == Role.HOST) {
                lease = new Lease(ticket.world, ticket.owner, ticket.compatibility, this);
                controller = true;
                Lease old = leases.put(ticket.world, lease);
                if (old != null) old.close();
                TunnelFrames.send(channel, TunnelWire.REGISTERED, lease.id);
            } else {
                lease = ticket.lease;
                if (lease == null || !lease.current()) {
                    reject();
                    return;
                }
                lease.connections.add(this);
                if (!lease.current() || lease.connections.size() > 65) {
                    reject();
                    return;
                }
                if (ticket.role == Role.JOIN) {
                    Grant attach = issue(new Ticket(Role.ATTACH, ticket.world, ticket.owner, ticket.compatibility, lease,
                        new Pending(this, lease), ticket.guestUser, ticket.consent, ticker.getAsLong() + TimeUnit.SECONDS.toNanos(10)));
                    TunnelFrames.send(lease.control.channel(), TunnelWire.OPEN, attach.token() + "\n" + ticket.guestUser + "\n" + (ticket.consent ? "1" : "0"));
                } else {
                    Peer guest = ticket.pending.guest;
                    if (!guest.channel().isActive() || guest.ready || ticket.pending.lease != lease) {
                        reject();
                        return;
                    }
                    pair(guest);
                    guest.pair(this);
                    TunnelFrames.send(channel, TunnelWire.READY, "");
                    TunnelFrames.send(guest.channel(), TunnelWire.READY, "");
                }
            }
        }

        private void reject() {
            if (channel.isActive()) {
                TunnelFrames.send(channel, TunnelWire.ERROR, "TUNNEL_REJECTED").addListener(ChannelFutureListener.CLOSE);
            } else close();
        }

        @Override
        protected void closed() {
            if (lease == null) return;
            lease.connections.remove(this);
            if (controller && leases.remove(lease.world, lease)) {
                lease.close();
                try {
                    authority.execute(() -> {
                        synchronized (worlds) {
                            // 旧控制通道的迟到回调不能撤下同一任期的新登记。
                            if (!leases.containsKey(lease.world)) worlds.tunnelOffline(lease.world, lease.owner);
                        }
                    });
                } catch (RejectedExecutionException ignored) { /* 服务正在退出，不释放世界所有权。 */ }
            }
        }

    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            tickets.clear();
            latestHostTickets.clear();
            leases.values().forEach(Lease::close);
            leases.clear();
            channels.close();
            authority.shutdownNow();
            acceptor.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

}