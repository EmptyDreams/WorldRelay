package top.kmar.worldrelay.server.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;

import static top.kmar.worldrelay.tunnel.TunnelWire.*;

/** 服务端 Netty 帧处理；客户端和共享协议不依赖 Netty。 */
final class TunnelFrames {

    private TunnelFrames() {
    }

    static void install(ChannelPipeline pipeline, boolean webSocket) {
        if (!webSocket) {
            pipeline.addLast(new LengthFieldBasedFrameDecoder(CHUNK + 5, 0, 4, 0, 4));
            pipeline.addLast(new LengthFieldPrepender(4));
        }
        pipeline.addLast(new IdleStateHandler(45, 15, 0));
        pipeline.addLast(new ChannelDuplexHandler() {
            private long pending;

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                int bytes = msg instanceof ByteBuf buffer ? buffer.readableBytes() : 0;
                if (pending + bytes > MAX_PENDING) {
                    ReferenceCountUtil.release(msg);
                    promise.tryFailure(new IllegalStateException("Tunnel write queue is full"));
                    ctx.close();
                    return;
                }
                pending += bytes;
                promise.addListener(done -> pending -= bytes);
                ctx.write(msg, promise);
            }
        });
    }

    static ChannelFuture send(Channel channel, byte type, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw new IllegalArgumentException("Control message too large");
        return channel.writeAndFlush(channel.alloc().buffer(bytes.length + 1).writeByte(type).writeBytes(bytes));
    }

    static void data(Channel channel, ByteBuf bytes) {
        channel.writeAndFlush(channel.alloc().buffer(bytes.readableBytes() + 1).writeByte(DATA)
            .writeBytes(bytes, bytes.readerIndex(), bytes.readableBytes()));
    }

    static String text(ByteBuf body) {
        if (body.readableBytes() > 4096) throw new IllegalArgumentException("Control message too large");
        return body.toString(StandardCharsets.UTF_8);
    }

}