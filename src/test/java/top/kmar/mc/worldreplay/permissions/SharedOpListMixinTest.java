package top.kmar.mc.worldreplay.permissions;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 在真实 1.21.1 PlayerList 上验证构造器注入、原版 OP 操作和最终权限等级。 */
class SharedOpListMixinTest {

    @TempDir
    Path saves;

    private final GameProfile owner = new GameProfile(UUID.randomUUID(), "Host");
    private final GameProfile guest = new GameProfile(UUID.randomUUID(), "Guest");

    @Test
    void sharedWorldUsesMemoryListForVanillaOpAndDeop() throws Exception {
        try (var world = new World(true)) {
            assertInstanceOf(MemoryOpList.class, world.players.getOps());
            assertTrue(world.players.getOps().isEmpty());
            world.players.getOps().load();
            world.players.op(guest);
            assertTrue(world.players.isOp(guest));
            assertEquals(2, world.server.getProfilePermissions(guest));
            world.players.getOps().save();
            world.players.getOps().load();
            assertTrue(world.players.isOp(guest));
            world.players.deop(guest);
            assertFalse(world.players.isOp(guest));
            assertEquals(0, world.server.getProfilePermissions(guest));
        }
    }

    /** 房主、创建世界时的作弊设置和 LAN 全员开关都不能绕过共享世界的名单。 */
    @Test
    void sharedHostAndGuestsReceiveOnlyExplicitListPermissions() throws Exception {
        try (var world = new World(true)) {
            when(world.data.isAllowCommands()).thenReturn(true);
            world.players.setAllowCommandsForAllPlayers(true);
            assertFalse(world.players.isOp(owner));
            assertFalse(world.players.isOp(guest));
            assertEquals(0, world.server.getProfilePermissions(owner));
            assertEquals(0, world.server.getProfilePermissions(guest));

            world.players.getOps().add(new ServerOpListEntry(owner, 2, false));
            world.players.getOps().add(new ServerOpListEntry(guest, 3, false));
            assertEquals(2, world.server.getProfilePermissions(owner));
            assertEquals(3, world.server.getProfilePermissions(guest));

            world.players.deop(owner);
            assertFalse(world.players.isOp(owner));
            assertEquals(0, world.server.getProfilePermissions(owner));
            assertEquals(3, world.server.getProfilePermissions(guest));
        }
    }

    /** 普通世界继续使用原版列表与房主／LAN 权限，不替换其持久化实现。 */
    @Test
    void ordinaryWorldRetainsVanillaListAndPermissionRules() throws Exception {
        try (var world = new World(false)) {
            assertEquals(ServerOpList.class, world.players.getOps().getClass());
            assertFalse(world.players.isOp(owner));
            when(world.data.isAllowCommands()).thenReturn(true);
            assertTrue(world.players.isOp(owner));
            assertEquals(4, world.server.getProfilePermissions(owner));
            assertFalse(world.players.isOp(guest));
            world.players.setAllowCommandsForAllPlayers(true);
            assertTrue(world.players.isOp(guest));
            assertEquals(4, world.server.getProfilePermissions(guest));
        }
    }

    private final class World implements AutoCloseable {
        private final LevelStorageSource.LevelStorageAccess access;
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final WorldData data = mock(WorldData.class);
        private final PlayerList players;

        @SuppressWarnings("DataFlowIssue") // OP 操作与权限计算不访问注册表。
        private World(boolean shared) throws Exception {
            Path root = Files.createTempDirectory(saves, "ops-");
            if (shared) Files.createFile(root.resolve(SharedPlayerData.MARKER_FILE));
            access = LevelStorageSource.createDefault(saves).validateAndCreateAccess(root.getFileName().toString());
            players = new PlayerList(server, null, access.createPlayerStorage(), 8) { };
            when(server.getPlayerList()).thenReturn(players);
            when(server.getWorldData()).thenReturn(data);
            when(server.isSingleplayerOwner(owner)).thenReturn(true);
            when(server.getOperatorUserPermissionLevel()).thenReturn(2);
            when(server.isSingleplayer()).thenReturn(true);
            when(server.getProfilePermissions(any())).thenCallRealMethod();
        }

        @Override
        public void close() throws Exception {
            access.close();
        }
    }

}
