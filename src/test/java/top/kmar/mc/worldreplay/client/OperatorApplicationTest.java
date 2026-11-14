package top.kmar.mc.worldreplay.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;
import top.kmar.worldrelay.permissions.OperatorPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class OperatorApplicationTest {
    @TempDir Path saves;

    /** 执行实际应用入口，确认在线房主的授权和撤销均发送权限包与新指令树。 */
    @SuppressWarnings("DataFlowIssue")
    @Test
    void appliesPolicyBeforeLoginAndRefreshesLivePlayerPermissions() throws Exception {
        Path root = Files.createDirectory(saves.resolve("shared"));
        Files.createFile(root.resolve(SharedPlayerData.MARKER_FILE));
        var session = new LocalWorldSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 1, LocalWorldSession.Stage.STARTED, "shared");
        var constructor = SharedWorldCreation.Creation.class.getDeclaredConstructor(Screen.class, String.class,
            ClientConnectionSettings.class, LocalWorldSessionStore.class, LocalWorldSession.class, boolean.class);
        constructor.setAccessible(true);
        var creation = constructor.newInstance(null, "Host", null, null, session, false);
        var directory = SharedWorldCreation.Creation.class.getDeclaredField("worldDirectory");
        directory.setAccessible(true);
        directory.set(creation, root.toAbsolutePath().normalize());
        var policy = SharedWorldCreation.Creation.class.getDeclaredField("operatorPolicy");
        policy.setAccessible(true);
        var active = SharedWorldCreation.class.getDeclaredField("active");
        active.setAccessible(true);
        Object previous = active.get(null);
        try (var access = LevelStorageSource.createDefault(saves).validateAndCreateAccess("shared")) {
            var profile = new GameProfile(UUID.randomUUID(), "Host");
            var server = mock(MinecraftServer.class);
            var player = mock(ServerPlayer.class);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            var commands = mock(Commands.class);
            PlayerList players = spy(new PlayerList(server, null, access.createPlayerStorage(), 8) { });
            doReturn(List.of(player)).when(players).getPlayers();
            when(server.getWorldPath(LevelResource.ROOT)).thenReturn(root);
            when(server.getPlayerList()).thenReturn(players);
            when(server.getCommands()).thenReturn(commands);
            when(server.isSingleplayerOwner(profile)).thenReturn(true);
            when(server.getProfilePermissions(profile)).thenCallRealMethod();
            when(player.getGameProfile()).thenReturn(profile);
            active.set(null, creation);
            assertThrows(IllegalStateException.class, () -> SharedWorldCreation.applyOperators(server));

            policy.set(creation, new OperatorPolicy(1, Map.of("Host", 2)));
            SharedWorldCreation.applyOperators(server);
            assertEquals(2, server.getProfilePermissions(profile));
            verify(player.connection).send(argThat((Packet<?> packet) -> packet instanceof ClientboundEntityEventPacket event && event.getEventId() == 26));
            policy.set(creation, new OperatorPolicy(2, Map.of()));
            SharedWorldCreation.applyOperators(server);
            assertEquals(0, server.getProfilePermissions(profile));
            verify(player.connection).send(argThat((Packet<?> packet) -> packet instanceof ClientboundEntityEventPacket event && event.getEventId() == 24));
            verify(commands, times(2)).sendCommands(player);
        } finally { active.set(null, previous); }
    }
}
