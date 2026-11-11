package top.kmar.mc.worldreplay.client;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.UUIDUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SharedWorldLoginPolicyTest {
    /** 房主、访客与本地玩家数据确认使用同一个原版离线 UUID，不受启动器 UUID 或房主轮换影响。 */
    @Test
    void usesVanillaOfflineIdentityForEveryHostingPlayer() {
        String name = "Dev";
        var premium = new GameProfile(UUID.randomUUID(), name);
        var anotherLauncher = new GameProfile(UUID.randomUUID(), name);
        var guest = UUIDUtil.createOfflineProfile(name);
        var host = SharedWorldLoginPolicy.profile(premium, true);
        assertEquals(guest, host);
        assertEquals(host, SharedWorldLoginPolicy.profile(anotherLauncher, true));
        assertEquals(host.getId(), SharedWorldLoginPolicy.playerId(name, premium.getId(), true));
        assertEquals(host.getId(), SharedWorldLoginPolicy.playerId(name, anotherLauncher.getId(), true));
        assertNotEquals(host.getId(), SharedWorldLoginPolicy.playerId("AnotherPlayer", premium.getId(), true));
        assertSame(premium, SharedWorldLoginPolicy.profile(premium, false));
        assertEquals(premium.getId(), SharedWorldLoginPolicy.playerId(name, premium.getId(), false));
    }

    /** 在真实 1.21.1 类上装配客户端注入，避免初始化登录模式的目标方法签名失配。 */
    @Test
    void appliesPolicyMixinToActualIntegratedServer() {
        assertTrue(Arrays.stream(IntegratedServer.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().contains("worldrelay$loginPolicy")));
    }

    /** 世界列表读取固定登录模式，拒绝把字符串等非法类型误当成开关。 */
    @Test
    void parsesWorldLoginPolicy() throws Exception {
        String body = "{\"worlds\":[{\"worldId\":\"" + UUID.randomUUID()
            + "\",\"name\":\"离线世界\",\"status\":\"FREE\",\"hostName\":null,"
            + "\"playerCount\":0,\"lastCommittedAt\":null,\"allowOfflinePlayers\":true}]}";
        assertTrue(SharedWorldListClient.parse(body).getFirst().allowOfflinePlayers());
        assertFalse(SharedWorldListClient.parse(body.replace(":true", ":false")).getFirst().allowOfflinePlayers());
        assertThrows(IOException.class, () -> SharedWorldListClient.parse(body.replace(":true", ":\"true\"")));
        var invalid = JsonParser.parseString(body).getAsJsonObject();
        invalid.getAsJsonArray("worlds").get(0).getAsJsonObject().add("allowOfflinePlayers", null);
        assertThrows(IOException.class, () -> SharedWorldListClient.parse(invalid.toString()));
    }
}
