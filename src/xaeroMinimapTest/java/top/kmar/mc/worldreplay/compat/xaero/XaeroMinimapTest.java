package top.kmar.mc.worldreplay.compat.xaero;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import xaero.hud.minimap.world.state.MinimapWorldStateUpdater;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class XaeroMinimapTest {

    private static MinimapWorldStateUpdater session(XaeroMapIdentity identity) throws Exception {
        var updater = new MinimapWorldStateUpdater(null, null, null);
        Field field = MinimapWorldStateUpdater.class.getDeclaredField("worldrelay$mapIdentity");
        field.setAccessible(true);
        field.set(updater, identity);
        return updater;
    }

    /** 实际路标目录在房主/访客及各维度间保持同一世界身份，退出后旧会话也不会借用新世界身份。 */
    @Test
    void keepsWaypointRootAndSubworldStableWithAndWithoutWorldMap() throws Exception {
        var identity = new XaeroMapIdentity(UUID.randomUUID().toString());
        var host = session(identity);
        var guest = session(identity);
        for (var updater : new MinimapWorldStateUpdater[]{host, guest}) {
            for (int format = 0; format <= 4; format++) {
                assertEquals(identity.root(), updater.getAutoRootContainerPath(format).toString());
            }
            for (boolean worldMap : new boolean[]{false, true}) {
                for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                    assertEquals("mw$0", updater.getPotentialWorldNode(dimension, worldMap));
                }
            }
        }
        var another = new XaeroMapIdentity(UUID.randomUUID().toString());
        assertNotEquals(host.getAutoRootContainerPath(4), session(another).getAutoRootContainerPath(4));
        assertEquals(host.getAutoRootContainerPath(4), guest.getAutoRootContainerPath(4));
    }

}