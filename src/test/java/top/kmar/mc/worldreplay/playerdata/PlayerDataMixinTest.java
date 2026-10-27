package top.kmar.mc.worldreplay.playerdata;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.playerdata.MissingPlayerDataConfirmation.Decision;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 在 NeoForge 环境中执行实际的登录检查，验证 Mixin 对加入行为的影响。 */
class PlayerDataMixinTest {

    @TempDir
    Path saves;

    private final GameProfile profile = new GameProfile(UUID.randomUUID(), "WorldRelayTest");
    private final InetSocketAddress address = new InetSocketAddress("127.0.0.1", 25565);

    /**
     * 实际登录检查应拒绝共享世界中尚未确认的新玩家，明确确认后允许重试加入；
     * 登录检查本身不应创建玩家文件。
     */
    @Test
    void sharedWorldRequiresConsentBeforeAdmittingANewPlayer() throws Exception {
        Path world = Files.createDirectory(saves.resolve("shared"));
        Files.createFile(world.resolve(SharedPlayerData.MARKER_FILE));
        try (var access = LevelStorageSource.createDefault(saves).validateAndCreateAccess("shared")) {
            PlayerDataStorage storage = access.createPlayerStorage();
            SharedPlayerData data = ((SharedPlayerData.Access) storage).worldrelay$getSharedPlayerData();
            // 此登录检查路径不访问服务器和注册表，无需启动完整的游戏服务器。
            @SuppressWarnings("DataFlowIssue")
            PlayerList players = new PlayerList(null, null, storage, 8) {
            };
            assertNotNull(players.canPlayerLogin(address, profile), "缺少玩家数据且未确认时应拒绝加入");
            data.requestCreation(profile.getId(), request -> CompletableFuture.completedFuture(Decision.CREATE))
                .toCompletableFuture().join();
            assertNull(players.canPlayerLogin(address, profile), "明确确认新建后应允许重试加入");
            assertFalse(Files.exists(world.resolve("playerdata").resolve(profile.getId() + ".dat")));
        }
    }

    /**
     * 未标记为共享的普通世界，即使缺少玩家文件也应允许加入，
     * 验证共享世界的新玩家确认限制不会影响普通世界。
     */
    @Test
    void ordinaryWorldDoesNotRequireExistingPlayerData() throws Exception {
        try (var access = LevelStorageSource.createDefault(saves).validateAndCreateAccess("ordinary")) {
            PlayerDataStorage storage = access.createPlayerStorage();
            @SuppressWarnings("DataFlowIssue")
            PlayerList players = new PlayerList(null, null, storage, 8) {
            };
            assertNull(players.canPlayerLogin(address, profile), "普通世界不应受到共享世界初始化规则的限制");
        }
    }

}