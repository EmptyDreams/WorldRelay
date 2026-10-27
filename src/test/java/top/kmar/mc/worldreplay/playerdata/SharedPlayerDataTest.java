package top.kmar.mc.worldreplay.playerdata;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.playerdata.MissingPlayerDataConfirmation.Decision;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData.LoadException;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SharedPlayerDataTest {

    @TempDir
    Path world;

    private final UUID playerId = UUID.randomUUID();
    private SharedPlayerData data;

    @BeforeEach
    void markSharedWorld() throws IOException {
        Files.createFile(world.resolve(SharedPlayerData.MARKER_FILE));
        Files.createDirectory(world.resolve("playerdata"));
        data = new SharedPlayerData(world);
    }

    /**
     * 旧备份和其他玩家文件同时存在时，只读取当前 UUID 的主文件；
     * 保留完整 NBT（包括未知附加字段），且不改写主文件。
     */
    @Test
    void readsCompletePrimaryNbtWithoutChangingIt() throws IOException {
        CompoundTag expected = playerTag("current player");
        CompoundTag modData = new CompoundTag();
        modData.putString("custom_attachment", "preserve me");
        expected.put("neoforge:attachments", modData);
        write(backup(), playerTag("stale backup"));
        UUID otherPlayerId = UUID.randomUUID();
        CompoundTag otherPlayer = playerTag("another player");
        otherPlayer.putUUID("UUID", otherPlayerId);
        write(world.resolve("playerdata").resolve(otherPlayerId + ".dat"), otherPlayer);
        write(primary(), expected);
        byte[] original = Files.readAllBytes(primary());

        assertEquals(Status.READY, data.check(playerId).status());
        assertEquals(expected, data.readPrimary(playerId).orElseThrow());
        assertArrayEquals(original, Files.readAllBytes(primary()));
    }

    /**
     * 主文件缺失但存在旧备份时，应要求确认新建；未确认或取消后均禁止读取，
     * 不创建主文件，也不改写旧备份。
     */
    @Test
    void missingPrimaryRequiresConsentInsteadOfUsingBackup() throws IOException {
        write(backup(), playerTag("old backup"));
        byte[] originalBackup = Files.readAllBytes(backup());

        assertEquals(Status.NEEDS_CREATION_CONFIRMATION, data.check(playerId).status());
        assertThrows(LoadException.class, () -> data.readPrimary(playerId));
        answerCreationPrompt(Decision.CANCEL);
        assertEquals(Status.NEEDS_CREATION_CONFIRMATION,
            assertThrows(LoadException.class, () -> data.readPrimary(playerId)).result().status());
        assertFalse(Files.exists(primary()));
        assertArrayEquals(originalBackup, Files.readAllBytes(backup()));
    }

    /**
     * 新建确认只为指定世界和 UUID 提供一次初始化机会；跨世界、跨玩家和重复使用
     * 都会被拒绝，读取层不会提前写出空白主文件。
     */
    @Test
    void confirmedCreationIsScopedAndSingleUse() throws IOException {
        answerCreationPrompt(Decision.CREATE);
        SharedPlayerData loadingData = new SharedPlayerData(world);
        assertThrows(LoadException.class, () -> loadingData.readPrimary(UUID.randomUUID()));
        Path anotherWorld = Files.createDirectory(world.resolve("another-world"));
        Files.createFile(anotherWorld.resolve(SharedPlayerData.MARKER_FILE));
        assertThrows(LoadException.class, () -> new SharedPlayerData(anotherWorld).readPrimary(playerId));
        assertTrue(loadingData.readPrimary(playerId).isEmpty());
        assertThrows(LoadException.class, () -> loadingData.readPrimary(playerId));
        // 由原版初始化玩家并在之后保存，此接口不会直接写出空白的 .dat 文件。
        assertFalse(Files.exists(primary()));
    }

    /**
     * 确认后主文件重新出现时，应加载已有数据；此次读取后文件再次消失时，
     * 必须重新确认，不能沿用之前的创建许可。
     */
    @Test
    void fileAppearingAfterConsentWinsAndConsumesConsent() throws IOException {
        answerCreationPrompt(Decision.CREATE);
        CompoundTag expected = playerTag("restored player");
        write(primary(), expected);
        assertEquals(expected, data.readPrimary(playerId).orElseThrow());
        Files.delete(primary());
        assertEquals(Status.NEEDS_CREATION_CONFIRMATION,
            assertThrows(LoadException.class, () -> data.readPrimary(playerId)).result().status());
    }

    /**
     * 即使已经确认新建且旧备份有效，主文件损坏时仍须报错，不能回退或初始化新玩家，
     * 也不能改写主文件和旧备份。
     */
    @Test
    void corruptPrimaryCannotBeReplacedByBackupOrCreationConsent() throws IOException {
        answerCreationPrompt(Decision.CREATE);
        Files.writeString(primary(), "corrupt");
        write(backup(), playerTag("valid backup"));
        byte[] originalBackup = Files.readAllBytes(backup());

        assertEquals(Status.ERROR, data.check(playerId).status());
        assertEquals(Status.ERROR, assertThrows(LoadException.class, () -> data.readPrimary(playerId)).result().status());
        assertEquals("corrupt", Files.readString(primary()));
        assertArrayEquals(originalBackup, Files.readAllBytes(backup()));
    }

    private void answerCreationPrompt(Decision decision) {
        data.requestCreation(playerId, request -> CompletableFuture.completedFuture(decision))
            .toCompletableFuture().join();
    }

    private Path primary() {
        return world.resolve("playerdata").resolve(playerId + ".dat");
    }

    private Path backup() {
        return world.resolve("playerdata").resolve(playerId + ".dat_old");
    }

    private CompoundTag playerTag(String identity) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", playerId);
        tag.putInt("DataVersion", 3955);
        tag.putString("test_identity", identity);
        return tag;
    }

    private void write(Path file, CompoundTag tag) throws IOException {
        NbtIo.writeCompressed(tag, file);
    }

}