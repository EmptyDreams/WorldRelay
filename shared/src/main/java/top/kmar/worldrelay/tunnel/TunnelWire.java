package top.kmar.worldrelay.tunnel;

/** v1：每条连接独立传输；WSS 消息与 TCP 长度帧具有相同的类型字节和负载。 */
public final class TunnelWire {

    public static final byte HELLO = 1, REGISTERED = 2, OPEN = 3, READY = 4, DATA = 5, PING = 6, PONG = 7, CLOSE = 9;
    // 共享源在客户端模块中也被分析；该类型由服务端发送。
    @SuppressWarnings("unused")
    public static final byte ERROR = 8;
    public static final int CHUNK = 64 * 1024;
    public static final int MAX_PENDING = 1024 * 1024;
    public static final String PATH = "/api/v1/tunnel";

    private TunnelWire() {
    }

}