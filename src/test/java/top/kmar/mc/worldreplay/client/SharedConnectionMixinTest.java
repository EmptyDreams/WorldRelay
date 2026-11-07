package top.kmar.mc.worldreplay.client;

import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 在实际 NeoForge 测试环境加载目标类，验证 1.21.1 Mixin 签名和普通地址解析。 */
class SharedConnectionMixinTest {

    private static ServerNameResolver resolver(AtomicInteger dnsCalls, AtomicInteger srvCalls) throws ReflectiveOperationException {
        ServerAddressResolver dns = address -> {
            dnsCalls.incrementAndGet();
            return Optional.of(ResolvedServerAddress.from(new InetSocketAddress(InetAddress.getLoopbackAddress(), address.getPort())));
        };
        ServerRedirectHandler srv = address -> {
            srvCalls.incrementAndGet();
            return Optional.empty();
        };
        AddressCheck allowed = new AddressCheck() {
            @Override
            public boolean isAllowed(@NotNull ResolvedServerAddress address) {
                return true;
            }

            @Override
            public boolean isAllowed(@NotNull ServerAddress address) {
                return true;
            }
        };
        // 1.21.1 的测试构造器为包级可见；替换解析依赖，仍执行实际 Minecraft 类及其 Mixin。
        var constructor = ServerNameResolver.class.getDeclaredConstructor(ServerAddressResolver.class, ServerRedirectHandler.class, AddressCheck.class);
        constructor.setAccessible(true);
        return constructor.newInstance(dns, srv, allowed);
    }

    /** 即使 DNS 能解析任意域名，未登记的共享地址也在 DNS／SRV 前被拒绝，普通地址仍走原版流程。 */
    @Test
    void resolvesOnlyRegisteredSharedAddressesWithoutChangingOrdinaryServers() throws Exception {
        var dnsCalls = new AtomicInteger();
        var srvCalls = new AtomicInteger();
        ServerNameResolver resolver = resolver(dnsCalls, srvCalls);
        String host = UUID.randomUUID() + ".localhost";
        assertTrue(SharedWorldConnection.managedHost(host));
        assertTrue(resolver.resolveAddress(new ServerAddress(host, 25565)).isEmpty());
        assertTrue(resolver.resolveAddress(new ServerAddress(host, 25566)).isEmpty());
        assertEquals(0, dnsCalls.get(), "共享地址不能交给系统 DNS");
        assertEquals(0, srvCalls.get(), "共享地址不能尝试 SRV 重定向");
        assertTrue(resolver.resolveAddress(new ServerAddress("127.0.0.1", 25565)).isPresent());
        assertTrue(resolver.resolveAddress(new ServerAddress("ordinary.localhost", 25565)).isPresent());
        assertEquals(2, dnsCalls.get());
        assertEquals(2, srvCalls.get());
        assertFalse(SharedWorldConnection.managedHost("ordinary.localhost"));
    }

    /** 确认无画面测试环境可以加载连接线程、内置服务器和登录监听器。 */
    @Test
    void loadsClientConnectionAndIntegratedServerClasses() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        for (String type : new String[]{"net.minecraft.client.gui.screens.ConnectScreen$1",
            "net.minecraft.client.server.IntegratedServer", "net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl"}) {
            assertNotNull(Class.forName(type, false, loader).getDeclaredMethods());
        }
    }

}
