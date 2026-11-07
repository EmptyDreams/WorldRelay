package top.kmar.mc.worldreplay.compat.xaero;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.jetbrains.annotations.Nullable;
import top.kmar.mc.worldreplay.client.SharedWorldConnection;

import java.util.UUID;

/** 每个 Xaero 会话绑定一次身份；后台地图写入不再读取可能已切换的全局游戏状态。 */
public record XaeroMapIdentity(String worldId) {

    public static final int LEVEL_ID = 0;
    public static final String SUB_WORLD = "mw$" + LEVEL_ID;

    public XaeroMapIdentity {
        if (!UUID.fromString(worldId).toString().equals(worldId)) throw new IllegalArgumentException("Invalid shared world ID");
    }

    public String root() {
        return "Multiplayer_" + worldId + ".localhost";
    }

    public static @Nullable XaeroMapIdentity capture(ClientPacketListener listener) {
        String world = SharedWorldConnection.worldId(listener);
        return world == null ? null : new XaeroMapIdentity(world);
    }

}
