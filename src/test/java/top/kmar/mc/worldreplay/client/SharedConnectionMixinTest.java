package top.kmar.mc.worldreplay.client;

import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在实际 NeoForge 测试环境加载目标类，验证 1.21.1 Mixin 签名和普通地址解析。 */
class SharedConnectionMixinTest {

    /** 未建立入口的受管域名应失败，普通回环服务器仍可走原版解析。 */
    @Test
    void resolvesOnlyRegisteredSharedAddressesWithoutChangingOrdinaryServers() {
        String host = UUID.randomUUID() + ".localhost";
        assertTrue(SharedWorldConnection.managedHost(host));
        assertTrue(ServerNameResolver.DEFAULT.resolveAddress(new ServerAddress(host, 25565)).isEmpty());
        assertTrue(ServerNameResolver.DEFAULT.resolveAddress(new ServerAddress("127.0.0.1", 25565)).isPresent());
        assertFalse(SharedWorldConnection.managedHost("ordinary.localhost"));
    }

    /** 实际转换连接线程、内置服务器和登录监听器，提前发现匿名类目标或方法描述符不匹配。 */
    @Test
    void appliesConnectionPublishAndLoginMixinsToMinecraftClasses() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        for (String type : new String[]{"net.minecraft.client.gui.screens.ConnectScreen$1",
            "net.minecraft.client.server.IntegratedServer", "net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl"}) {
            assertNotNull(Class.forName(type, false, loader).getDeclaredMethods());
        }
    }

}