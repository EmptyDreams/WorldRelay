package top.kmar.mc.worldreplay.playerdata;

import com.mojang.authlib.GameProfile;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/** 只登记已鉴权隧道在房主本机创建的 TCP 连接；不接收客户端提供的玩家 UUID。 */
public final class TunnelPlayerAccess {

    private record Admission(String username) {

    }

    private static final ConcurrentHashMap<SocketAddress, Admission> ADMISSIONS = new ConcurrentHashMap<>();

    private TunnelPlayerAccess() {
    }

    public static AutoCloseable register(SocketAddress address, String username) {
        Admission admission = new Admission(username);
        if (ADMISSIONS.putIfAbsent(address, admission) != null) throw new IllegalStateException("Duplicate tunnel socket");
        return () -> ADMISSIONS.remove(address, admission);
    }

    public static boolean matches(SocketAddress address, GameProfile profile) {
        Admission admission = ADMISSIONS.get(address);
        return admission == null || admission.username.equals(profile.getName());
    }

}
