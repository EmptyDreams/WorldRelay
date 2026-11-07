package top.kmar.mc.worldreplay.compat.ipn;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.WorldData;
import org.anti_ad.mc.ipnext.config.EditProfiles;
import org.anti_ad.mc.ipnext.config.LockedSlotsSettings;
import org.anti_ad.mc.ipnext.event.LockSlotsHandler;
import org.anti_ad.mc.ipnext.parser.LockSlotsLoader;
import org.anti_ad.mc.ipnext.parser.ProfilesLoader;
import org.anti_ad.mc.ipnext.parser.RefillSlotsLoader;
import org.anti_ad.mc.ipnext.parser.VillagerBookmarksLoader;
import org.anti_ad.mc.ipnext.specific.SmallHelpersKt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import top.kmar.mc.worldreplay.client.SharedWorldCreation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实 IPN 发布类及 Kotlin 内联后的路径分支，Minecraft 运行环境由夹具提供。 */
class IpnWorldIdentityTest {

    @TempDir
    static Path game;

    private static final class Session implements AutoCloseable {

        final Minecraft client = mock(Minecraft.class);
        final MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
        final MockedStatic<SharedWorldCreation> creation = mockStatic(SharedWorldCreation.class);
        final String worldId = UUID.randomUUID().toString();
        final IntegratedServer host = host("Original world", worldId);
        final boolean profilesPerServer;
        final boolean locksPerServer;

        Session() throws Exception {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            try {
                var directory = Minecraft.class.getField("gameDirectory");
                directory.setAccessible(true);
                directory.set(client, game.toFile());
                // 按 IPN 客户端初始化的入口顺序建立互相引用的 Kotlin 配置单例。
                Class.forName("org.anti_ad.mc.ipnext.config.ConfigScreenSettings");
                profilesPerServer = EditProfiles.INSTANCE.getPROFILES_PER_SERVER().getBooleanValue();
                locksPerServer = LockedSlotsSettings.INSTANCE.getENABLE_LOCK_SLOTS_PER_SERVER().getBooleanValue();
                EditProfiles.INSTANCE.getPROFILES_PER_SERVER().setValue(true);
                LockedSlotsSettings.INSTANCE.getENABLE_LOCK_SLOTS_PER_SERVER().setValue(true);
                asHost(host);
            } catch (Throwable failure) {
                creation.close();
                minecraft.close();
                throw failure;
            }
        }

        IntegratedServer host(String name, String world) {
            IntegratedServer server = mock(IntegratedServer.class);
            WorldData data = mock(WorldData.class);
            when(data.getLevelName()).thenReturn(name);
            when(server.getWorldData()).thenReturn(data);
            creation.when(() -> SharedWorldCreation.worldId(server)).thenReturn(world);
            return server;
        }

        void asHost(IntegratedServer server) {
            when(client.isLocalServer()).thenReturn(true);
            when(client.getSingleplayerServer()).thenReturn(server);
            when(client.getCurrentServer()).thenReturn(null);
        }

        void asGuest(String address) {
            when(client.isLocalServer()).thenReturn(false);
            when(client.getSingleplayerServer()).thenReturn(null);
            when(client.getCurrentServer()).thenReturn(new ServerData("Display name", address, ServerData.Type.OTHER));
        }

        @Override
        public void close() {
            EditProfiles.INSTANCE.getPROFILES_PER_SERVER().setValue(profilesPerServer);
            LockedSlotsSettings.INSTANCE.getENABLE_LOCK_SLOTS_PER_SERVER().setValue(locksPerServer);
            creation.close();
            minecraft.close();
        }

    }

    private static List<Path> paths() {
        return List.of(ProfilesLoader.INSTANCE.getFile(), ProfilesLoader.INSTANCE.getOldfile(),
            LockSlotsLoader.INSTANCE.getFile(), LockSlotsLoader.INSTANCE.getFileOld(),
            RefillSlotsLoader.INSTANCE.getFile(), VillagerBookmarksLoader.INSTANCE.getPath());
    }

    /** 四类配置及旧格式路径在房主、访客和改名后的新房主之间一致，不同共享世界各自隔离。 */
    @Test
    void keepsAllPathsStableAcrossHostingRolesAndWorldNames() throws Exception {
        try (Session session = new Session()) {
            List<Path> hostPaths = paths();
            assertTrue(hostPaths.stream().allMatch(path -> path.toString().contains(session.worldId)));
            assertEquals(session.worldId + ".localhost", SmallHelpersKt.serverIdentifier(true));
            session.asGuest(session.worldId + ".localhost");
            assertEquals(hostPaths, paths());
            session.asHost(session.host("Renamed on another computer", session.worldId));
            assertEquals(hostPaths, paths());
            session.asHost(session.host("Original world", UUID.randomUUID().toString()));
            List<Path> other = paths();
            for (int index = 0; index < hostPaths.size(); index++) {
                assertNotEquals(hostPaths.get(index), other.get(index));
            }
        }
    }

    /** 真正保存锁定槽位后，切成访客和另一房主仍能读写同一份个人配置，其他世界保持独立。 */
    @Test
    @SuppressWarnings("unchecked")
    // IPN 发布类的集合签名擦除了元素类型。
    void savesAndReloadsLockedSlotsAcrossHostGuestHostTransitions() throws Exception {
        try (Session session = new Session()) {
            LockSlotsLoader.INSTANCE.load();
            Set<Integer> slots = LockSlotsHandler.INSTANCE.getLockedInvSlotsStoredValue();
            slots.addAll(Set.of(2, 7, 19));
            LockSlotsLoader.INSTANCE.save();
            Path file = LockSlotsLoader.INSTANCE.getFile();
            assertTrue(Files.isRegularFile(file));
            session.asGuest(session.worldId + ".localhost");
            slots.clear();
            LockSlotsLoader.INSTANCE.load();
            assertEquals(Set.of(2, 7, 19), slots);
            slots.clear();
            slots.addAll(Set.of(1, 5));
            LockSlotsLoader.INSTANCE.save();
            session.asHost(session.host("Another host", session.worldId));
            LockSlotsLoader.INSTANCE.load();
            assertEquals(Set.of(1, 5), slots);
            session.asHost(session.host("Another world", UUID.randomUUID().toString()));
            LockSlotsLoader.INSTANCE.load();
            assertTrue(slots.isEmpty());
            session.asHost(session.host);
            LockSlotsLoader.INSTANCE.load();
            assertEquals(Set.of(1, 5), slots);
        }
    }

    /** 按服务器配置关闭时仍使用全局配置；普通单机和普通服务器继续使用 IPN 原生身份。 */
    @Test
    void respectsGlobalSettingsAndOrdinaryWorldIdentity() throws Exception {
        try (Session session = new Session()) {
            Path root = ProfilesLoader.INSTANCE.getFile().getParent().getParent();
            EditProfiles.INSTANCE.getPROFILES_PER_SERVER().setValue(false);
            LockedSlotsSettings.INSTANCE.getENABLE_LOCK_SLOTS_PER_SERVER().setValue(false);
            assertEquals(root, ProfilesLoader.INSTANCE.getFile().getParent());
            assertEquals(root, LockSlotsLoader.INSTANCE.getFile().getParent());
            assertEquals(root, RefillSlotsLoader.INSTANCE.getFile().getParent());
            assertEquals("", SmallHelpersKt.serverIdentifier(false));
            assertEquals(root.resolve(session.worldId + ".localhost"), VillagerBookmarksLoader.INSTANCE.getPath());
            session.asGuest(session.worldId + ".localhost");
            assertEquals(root, ProfilesLoader.INSTANCE.getFile().getParent());

            EditProfiles.INSTANCE.getPROFILES_PER_SERVER().setValue(true);
            LockedSlotsSettings.INSTANCE.getENABLE_LOCK_SLOTS_PER_SERVER().setValue(true);
            session.asHost(session.host("OrdinarySingleplayer", null));
            assertEquals("OrdinarySingleplayer", SmallHelpersKt.serverIdentifier(true));
            assertEquals(root.resolve("OrdinarySingleplayer"), ProfilesLoader.INSTANCE.getFile().getParent());
            session.asGuest("play.example.net:25565");
            assertEquals("play.example.net&25565", SmallHelpersKt.serverIdentifier(true));
            assertEquals(root.resolve("play.example.net&25565"), LockSlotsLoader.INSTANCE.getFile().getParent());
        }
    }

}