package top.kmar.mc.worldreplay.compat.xaero;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.world.MapWorld;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实 NeoForge 变换器与官方 Xaero 发布类上执行，不以仿制类替代目标。 */
class XaeroWorldMapTest {

    private static final class HeadlessProcessor extends MapProcessor {

        HeadlessProcessor() throws Exception {
            super(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        }

        // 测试不初始化 OpenGL、物品显示和用户配置；身份与地图选择仍执行发布类的方法。
        @Override
        public void updateMapItem() {
        }

        @Override
        public void updateConfigValuesForMultipleThreads() {
        }

    }

    private static void bind(MapProcessor processor, XaeroMapIdentity identity) throws Exception {
        Field field = MapProcessor.class.getDeclaredField("worldrelay$mapIdentity");
        field.setAccessible(true);
        field.set(processor, identity);
    }

    private static Object invoke(MapProcessor processor, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = MapProcessor.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(processor, arguments);
    }

    /** 房主与访客的独立 Xaero 会话使用相同多人根目录和可写服务器层级，其他世界单独保存。 */
    @Test
    void keepsMapStorageStableAcrossHostingRolesAndSeparatesWorlds() throws Exception {
        var identity = new XaeroMapIdentity(UUID.randomUUID().toString());
        var host = new HeadlessProcessor();
        var guest = new HeadlessProcessor();
        bind(host, identity);
        bind(guest, identity);
        String expected = "Multiplayer_" + identity.worldId() + ".localhost";
        for (MapProcessor processor : new MapProcessor[]{host, guest}) {
            for (int format = 0; format <= 5; format++) {
                assertEquals(expected, invoke(processor, "getMainId", new Class<?>[]{int.class, ClientPacketListener.class}, format, null));
            }
            assertEquals(0, invoke(processor, "getAutoIdBase", new Class<?>[]{ClientLevel.class}, (Object) null));
            assertEquals(true, invoke(processor, "hasServerLevelId", new Class<?>[]{}));
            assertTrue(new MapWorld(expected, expected, processor).isMultiplayer());
        }
        var another = new XaeroMapIdentity(UUID.randomUUID().toString());
        bind(guest, another);
        assertEquals(another.root(), invoke(guest, "getMainId", new Class<?>[]{int.class, ClientPacketListener.class}, 5, null));
        assertEquals(expected, invoke(host, "getMainId", new Class<?>[]{int.class, ClientPacketListener.class}, 5, null));
        assertNotEquals(identity.root(), another.root());
    }

}