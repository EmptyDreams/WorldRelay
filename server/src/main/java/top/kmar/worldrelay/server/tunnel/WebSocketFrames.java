package top.kmar.worldrelay.server.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import top.kmar.worldrelay.tunnel.TunnelWire;

import java.util.List;

/** HTTP/WebSocket 编解码只在独立服务端使用，客户端使用 JDK WebSocket。 */
final class WebSocketFrames {

    private WebSocketFrames() {
    }

    static void install(ChannelPipeline pipeline) {
        pipeline.addLast(new WebSocketFrameAggregator(TunnelWire.CHUNK + 1));
        pipeline.addLast(new MessageToMessageCodec<WebSocketFrame, ByteBuf>() {
            @Override
            protected void encode(ChannelHandlerContext ctx, ByteBuf message, List<Object> out) {
                out.add(new BinaryWebSocketFrame(message.retain()));
            }

            @Override
            protected void decode(ChannelHandlerContext ctx, WebSocketFrame message, List<Object> out) {
                if (message instanceof BinaryWebSocketFrame && message.content().readableBytes() <= TunnelWire.CHUNK + 1)
                    out.add(message.content().retain());
                else ctx.close();
            }
        });
    }

}