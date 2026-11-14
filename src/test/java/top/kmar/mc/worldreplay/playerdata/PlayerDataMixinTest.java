package top.kmar.mc.worldreplay.playerdata;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.WorldData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 执行真实 PlayerList / PlayerDataStorage 和 Mixin，只替换服务器与玩家运行环境。 */
class PlayerDataMixinTest {

    @TempDir
    Path saves;

    private final GameProfile profile = new GameProfile(UUID.randomUUID(), "WorldRelayTest");
    private final InetSocketAddress address = new InetSocketAddress("127.0.0.1", 25565);

    /** 原版与 VanillaBackport 可以在首次保存前重复加载，新玩家无需确认或预写空文件。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newPlayerCanJoinAndBeLoadedRepeatedly(boolean shared) throws Exception {
        try (var world = new World(shared)) {
            assertNull(world.players.canPlayerLogin(address, profile));
            assertTrue(world.players.load(world.player).isEmpty());
            assertTrue(world.players.load(world.player).isEmpty());
            assertFalse(Files.exists(world.primary()));
            verify(world.player, never()).load(any());
        }
    }

    /** 交接后的房主读取自己的完整 UUID 数据，优先于旧备份，且不加载上一任房主的数据。 */
    @Test
    void sharedHostLoadsCompleteOwnUuidData() throws Exception {
        try (var world = new World(true)) {
            CompoundTag own = playerTag("current host");
            CompoundTag attachments = new CompoundTag();
            attachments.putString("custom_attachment", "preserve me");
            own.put("neoforge:attachments", attachments);
            NbtIo.writeCompressed(own, world.primary());
            NbtIo.writeCompressed(playerTag("stale backup"), world.backup());
            when(world.data.getLoadedPlayerTag()).thenReturn(playerTag("previous host"));
            byte[] original = Files.readAllBytes(world.primary());

            assertEquals(own, world.players.load(world.player).orElseThrow());
            assertEquals(own, world.players.load(world.player).orElseThrow());
            verify(world.player, times(2)).load(own);
            assertArrayEquals(original, Files.readAllBytes(world.primary()));
        }
    }

    /** 普通单人世界仍使用原版 level.dat 玩家数据分支。 */
    @Test
    void ordinaryHostStillLoadsLevelDatPlayer() throws Exception {
        try (var world = new World(false)) {
            CompoundTag singleplayer = playerTag("singleplayer");
            when(world.data.getLoadedPlayerTag()).thenReturn(singleplayer);
            NbtIo.writeCompressed(playerTag("uuid file"), world.primary());

            assertEquals(singleplayer, world.players.load(world.player).orElseThrow());
            verify(world.player).load(singleplayer);
        }
    }

    /** 主文件缺失或损坏时，保留原版 .dat_old 回退以及损坏文件备份行为。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fallsBackToVanillaBackupForMissingOrCorruptPrimary(boolean corrupt) throws Exception {
        try (var world = new World(true)) {
            CompoundTag backup = playerTag("backup");
            NbtIo.writeCompressed(backup, world.backup());
            byte[] originalBackup = Files.readAllBytes(world.backup());
            if (corrupt) Files.writeString(world.primary(), "corrupt");

            assertNull(world.players.canPlayerLogin(address, profile));
            assertEquals(backup, world.players.load(world.player).orElseThrow());
            verify(world.player).load(backup);
            assertArrayEquals(originalBackup, Files.readAllBytes(world.backup()));
            if (corrupt) {
                assertEquals("corrupt", Files.readString(world.primary()));
                try (var files = Files.list(world.primary().getParent())) {
                    assertTrue(files.anyMatch(path -> path.getFileName().toString()
                        .startsWith(profile.getId() + "_corrupted_")));
                }
            }
        }
    }

    /** 没有可用备份时也交给原版返回新玩家结果，不额外拒绝登录。 */
    @Test
    void corruptPrimaryWithoutBackupUsesVanillaNewPlayerBehavior() throws Exception {
        try (var world = new World(true)) {
            Files.writeString(world.primary(), "corrupt");
            assertNull(world.players.canPlayerLogin(address, profile));
            assertTrue(world.players.load(world.player).isEmpty());
            assertTrue(world.players.load(world.player).isEmpty());
            assertEquals("corrupt", Files.readString(world.primary()));
            verify(world.player, never()).load(any());
        }
    }

    /** 新玩家首次保存后，后续加载使用 UUID 文件并保留完整数据。 */
    @Test
    void savedNewPlayerReloadsFromOwnUuidFile() throws Exception {
        try (var world = new World(true)) {
            assertTrue(world.players.load(world.player).isEmpty());
            CompoundTag saved = playerTag("saved player");
            when(world.player.saveWithoutId(any(CompoundTag.class))).thenReturn(saved);
            world.storage.save(world.player);

            assertTrue(Files.isRegularFile(world.primary()));
            assertEquals(saved, world.players.load(world.player).orElseThrow());
            verify(world.player).load(saved);
        }
    }

    /** 自动初始化不取消隧道鉴权后的登录身份校验。 */
    @SuppressWarnings("unused") // admission 在连接关闭时清理。
    @Test
    void authenticatedTunnelStillRejectsWrongLoginIdentity() throws Exception {
        try (var world = new World(true);
             var admission = TunnelPlayerAccess.register(address, profile.getName())) {
            assertNull(world.players.canPlayerLogin(address, profile));
            assertEquals(Component.translatable("worldrelay.disconnect.tunnel_identity_mismatch"),
                world.players.canPlayerLogin(address, new GameProfile(UUID.randomUUID(), "AnotherPlayer")));
        }
    }

    private CompoundTag playerTag(String identity) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", profile.getId());
        tag.putInt("DataVersion", 3955);
        tag.putString("test_identity", identity);
        return tag;
    }

    private final class World implements AutoCloseable {
        private final LevelStorageSource.LevelStorageAccess access;
        private final PlayerDataStorage storage;
        private final WorldData data = mock(WorldData.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final PlayerList players;

        @SuppressWarnings("DataFlowIssue") // 此加载路径无需注册表。
        private World(boolean shared) throws Exception {
            Path root = Files.createTempDirectory(saves, "world-");
            if (shared) Files.createFile(root.resolve(SharedPlayerData.MARKER_FILE));
            access = LevelStorageSource.createDefault(saves).validateAndCreateAccess(root.getFileName().toString());
            storage = access.createPlayerStorage();
            MinecraftServer server = mock(MinecraftServer.class);
            when(server.getWorldData()).thenReturn(data);
            when(server.isSingleplayerOwner(profile)).thenReturn(true);
            when(player.getGameProfile()).thenReturn(profile);
            when(player.getUUID()).thenReturn(profile.getId());
            when(player.getStringUUID()).thenReturn(profile.getId().toString());
            when(player.getName()).thenReturn(Component.literal(profile.getName()));
            players = new PlayerList(server, null, storage, 8) { };
        }

        private Path primary() {
            return storage.getPlayerDir().toPath().resolve(profile.getId() + ".dat");
        }

        private Path backup() {
            return storage.getPlayerDir().toPath().resolve(profile.getId() + ".dat_old");
        }

        @Override
        public void close() throws Exception {
            access.close();
        }
    }

}
