package top.kmar.mc.worldreplay.compat.jei;

import com.mojang.serialization.Codec;
import mezz.jei.common.Internal;
import mezz.jei.common.util.ServerConfigPathUtil;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.config.BookmarkJsonConfig;
import mezz.jei.gui.config.LegacyBookmarkConfig;
import mezz.jei.gui.config.LookupHistoryJsonConfig;
import mezz.jei.modshade.net.mezzdev.deduplicatingrunner.DelayedTaskScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import top.kmar.mc.worldreplay.client.SharedWorldConnection;
import top.kmar.mc.worldreplay.client.SharedWorldCreation;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在实际 JEI 发布类上验证路径和延迟读写，测试数据存放在临时目录。 */
@SuppressWarnings("deprecation") // 旧版书签路径也属于兼容范围。
class JeiWorldPathTest {

    @TempDir
    Path base;

    private static final Codec<IBookmark> CODEC = Codec.STRING.fieldOf("entry").codec().xmap(
        name -> mock(IngredientBookmark.class, withSettings().name(name)),
        JeiWorldPathTest::name);

    private static String name(IBookmark bookmark) {
        return mockingDetails(bookmark).getMockCreationSettings().getMockName().toString();
    }

    private static Field connectionField(String name) throws Exception {
        Field field = SharedWorldConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Session implements AutoCloseable {

        final Minecraft client = mock(Minecraft.class);
        final MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
        final MockedStatic<SharedWorldCreation> creation = mockStatic(SharedWorldCreation.class);
        final List<SharedWorldConnection> guests = new ArrayList<>();
        final ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        final Object previousPending;
        final Object previousActive;
        ClientPacketListener listener;

        Session() throws Exception {
            // forgejunitdev 的测试线程默认指向库加载器；JEI 的 ServiceLoader 需使用游戏类加载器。
            Thread.currentThread().setContextClassLoader(JeiWorldPathTest.class.getClassLoader());
            previousPending = connectionField("pending").get(null);
            previousActive = connectionField("active").get(null);
            connectionField("pending").set(null, null);
            connectionField("active").set(null, null);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
        }

        private Connection connect(boolean local, ServerData data) {
            Connection connection = mock(Connection.class);
            when(connection.isMemoryConnection()).thenReturn(local);
            listener = mock(ClientPacketListener.class);
            when(listener.getConnection()).thenReturn(connection);
            when(listener.getServerData()).thenReturn(data);
            when(client.getConnection()).thenReturn(listener);
            when(client.getCurrentServer()).thenReturn(data);
            return connection;
        }

        void host(String world, String folder) throws Exception {
            IntegratedServer server = mock(IntegratedServer.class);
            LevelStorageSource.LevelStorageAccess storage = mock(LevelStorageSource.LevelStorageAccess.class);
            when(storage.getLevelId()).thenReturn(folder);
            Field field = MinecraftServer.class.getDeclaredField("storageSource");
            field.setAccessible(true);
            field.set(server, storage);
            creation.when(() -> SharedWorldCreation.worldId(server)).thenReturn(world);
            connect(true, null);
            when(client.getSingleplayerServer()).thenReturn(server);
        }

        void guest(String world, String name) throws Exception {
            var constructor = SharedWorldConnection.class.getDeclaredConstructor(Screen.class, String.class, String.class);
            constructor.setAccessible(true);
            SharedWorldConnection guest = constructor.newInstance(null, world, name);
            guests.add(guest);
            var address = new InetSocketAddress("127.0.0.1", 25123);
            Connection connection = connect(false, new ServerData(name, world + ".localhost", ServerData.Type.OTHER));
            when(connection.getRemoteAddress()).thenReturn(address);
            when(client.getSingleplayerServer()).thenReturn(null);
            connectionField("local").set(guest, address);
            connectionField("minecraftConnection").set(guest, connection);
            connectionField("pending").set(null, guest);
        }

        void ordinary(String name, String address) throws Exception {
            connectionField("pending").set(null, null);
            connectionField("active").set(null, null);
            connect(false, new ServerData(name, address, ServerData.Type.OTHER));
            when(client.getSingleplayerServer()).thenReturn(null);
        }

        @Override
        public void close() throws Exception {
            try {
                guests.forEach(SharedWorldConnection::close);
                connectionField("pending").set(null, previousPending);
                connectionField("active").set(null, previousActive);
            } finally {
                creation.close();
                minecraft.close();
                Thread.currentThread().setContextClassLoader(previousLoader);
            }
        }

    }

    private Path path() {
        return ServerConfigPathUtil.getWorldPath(base).orElseThrow();
    }

    /** 房主、访客及改名后的新房主共用固定目录和旧书签入口，不同共享世界分别保存。 */
    @Test
    void keepsWorldPathsStableAcrossHostingRolesAndRenames() throws Exception {
        String world = UUID.randomUUID().toString();
        Path expected = base.resolve("world/server/" + world + ".localhost");
        try (Session session = new Session()) {
            session.host(world, "first-local-copy");
            assertEquals(expected, path());
            assertEquals(expected.resolve("bookmarks.ini"), LegacyBookmarkConfig.getPath(base).orElseThrow());
            session.guest(world, "Display name");
            assertEquals(expected, path());
            session.guest(world, "Renamed shared world");
            assertEquals(expected, path());
            session.host(world, "another-local-copy");
            assertEquals(expected, path());
            String other = UUID.randomUUID().toString();
            session.guest(other, "Display name");
            assertEquals(base.resolve("world/server/" + other + ".localhost"), path());
        }
    }

    /** 收藏与查询历史按原生队列延迟保存；切换世界后旧任务仍写回原目录，返回时可完整重载。 */
    @Test
    @SuppressWarnings({"DataFlowIssue", "unchecked"})
    // 路径测试提供专用 Codec，不需要配方运行时；JEI 的部分集合签名为原始类型。
    void savesBookmarksAndHistoryToCapturedPathsAfterSwitchingWorlds() throws Exception {
        List<FutureTask<Void>> tasks = new ArrayList<>();
        DelayedTaskScheduler scheduler = (runnable, delay) -> {
            assertEquals(Duration.ofSeconds(5), delay);
            FutureTask<Void> future = new FutureTask<>(runnable, null);
            tasks.add(future);
            return future;
        };
        String world = UUID.randomUUID().toString();
        try (Session session = new Session(); MockedStatic<Internal> internal = mockStatic(Internal.class)) {
            internal.when(Internal::getDelayedExecutor).thenReturn(scheduler);
            session.host(world, "host-copy");
            Path original = path();
            BookmarkJsonConfig bookmarks = new BookmarkJsonConfig(base);
            LookupHistoryJsonConfig history = new LookupHistoryJsonConfig(base);
            IBookmark bookmark = mock(IngredientBookmark.class, withSettings().name("stone recipe"));
            assertTrue(bookmarks.saveBookmarks(null, null, null, null, RegistryAccess.EMPTY, null, List.of(bookmark), CODEC));
            history.save(null, null, RegistryAccess.EMPTY, null, List.of(bookmark), CODEC);
            assertEquals(2, tasks.size());
            assertFalse(Files.exists(original.resolve("bookmarks.json")));
            session.guest(UUID.randomUUID().toString(), "Other world");
            Path other = path();
            for (FutureTask<Void> task : tasks) {
                task.run();
                task.get();
            }
            assertTrue(Files.isRegularFile(original.resolve("bookmarks.json")));
            assertTrue(Files.isRegularFile(original.resolve("lookupHistory.json")));
            assertFalse(Files.exists(other.resolve("bookmarks.json")));
            assertFalse(Files.exists(other.resolve("lookupHistory.json")));
            session.guest(world, "Renamed world");
            List<IBookmark> loadedHistory = history.load(null, null, RegistryAccess.EMPTY, null, CODEC);
            assertEquals(List.of("stone recipe"), loadedHistory.stream().map(JeiWorldPathTest::name).toList());
            BookmarkList list = mock(BookmarkList.class);
            bookmarks.loadBookmarks(null, null, null, null, RegistryAccess.EMPTY, list, null, CODEC, null, null);
            ArgumentCaptor<List<IBookmark>> loaded = ArgumentCaptor.forClass(List.class);
            verify(list).setFromConfigFile(loaded.capture());
            assertEquals(1, loaded.getValue().size());
            assertEquals("stone recipe", name(loaded.getValue().getFirst()));
            session.host(world, "next-host-copy");
            assertEquals(original, path());
            assertEquals(1, history.load(null, null, RegistryAccess.EMPTY, null, CODEC).size());
        }
    }

    /** 普通单机及普通服务器沿用原生路径；相似域名和不匹配的连接不会冒用共享世界身份。 */
    @Test
    void preservesOrdinaryWorldsAndRequiresTheManagedConnection() throws Exception {
        try (Session session = new Session()) {
            session.host(null, "ordinary-save");
            assertEquals(base.resolve("world/local/ordinary-save"), path());
            session.ordinary("Ordinary", "play.example.net");
            assertEquals(base.resolve("world/server/Ordinary (play_example_net)"), path());
            String world = UUID.randomUUID().toString();
            session.guest(world, "Ordinary");
            Connection unrelated = mock(Connection.class);
            when(unrelated.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 25123));
            when(session.listener.getConnection()).thenReturn(unrelated);
            assertEquals(base.resolve("world/server/Ordinary (" + world + "_localhost)"), path());
            when(session.client.getConnection()).thenReturn(null);
            assertTrue(ServerConfigPathUtil.getWorldPath(base).isEmpty());
        }
    }

}