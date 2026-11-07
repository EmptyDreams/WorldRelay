package top.kmar.worldrelay.server.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import top.kmar.worldrelay.tunnel.TunnelWire;

/** 已配对隧道的字节转发；慢端暂停上游读取，业务处理不在此处执行文件 IO。 */
abstract class TunnelPeer extends SimpleChannelInboundHandler<ByteBuf> {

    protected Channel channel;
    private volatile TunnelPeer peer;
    protected volatile boolean ready;
    private final java.util.concurrent.atomic.AtomicBoolean finishing = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channel = ctx.channel();
    }

    @Override
    protected final void channelRead0(ChannelHandlerContext ctx, ByteBuf message) throws Exception {
        if (!message.isReadable() || message.readableBytes() > TunnelWire.CHUNK + 1) {
            ctx.close();
            return;
        }
        byte type = message.readByte();
        if (type == TunnelWire.PONG) return; // 活跃读取已重置失活计时。
        if (type == TunnelWire.PING) TunnelFrames.send(channel, TunnelWire.PONG, "");
        else if (type == TunnelWire.CLOSE) {
            if (peer != null) peer.finish();
            ctx.close();
        } else if (type == TunnelWire.DATA) {
            if (!ready || peer == null || !peer.channel.isActive()) {
                ctx.close();
                return;
            }
            TunnelFrames.data(peer.channel, message);
            channel.config().setAutoRead(peer.channel.isWritable());
        } else receive(type, message);
    }

    protected abstract void receive(byte type, ByteBuf body) throws Exception;

    public final Channel channel() {
        return channel;
    }

    public final void close() {
        if (channel != null) channel.close();
    }

    private void finish() {
        if (channel != null && channel.isActive() && finishing.compareAndSet(false, true))
            TunnelFrames.send(channel, TunnelWire.CLOSE, "").addListener(ChannelFutureListener.CLOSE);
    }

    public final void pair(TunnelPeer other) {
        peer = other;
        ready = true;
    }

    @Override
    public final void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (peer != null) updateReading(peer.channel, channel);
        ctx.fireChannelWritabilityChanged();
    }

    @SuppressWarnings("resource") // 事件循环由 Gateway 管理。
    private static void updateReading(Channel source, Channel destination) {
        // 更新排到上游自己的事件循环，执行时重读状态，避免恢复事件被暂停操作覆盖。
        source.eventLoop().execute(() -> {
            if (source.isActive()) source.config().setAutoRead(destination.isActive() && destination.isWritable());
        });
    }

    @Override
    public final void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.READER_IDLE) ctx.close();
            else TunnelFrames.send(channel, TunnelWire.PING, "");
        } else ctx.fireUserEventTriggered(event);
    }

    @Override
    public final void channelInactive(ChannelHandlerContext ctx) {
        if (peer != null) peer.finish();
        closed();
    }

    protected void closed() {
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

}