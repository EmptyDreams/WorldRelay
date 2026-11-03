package top.kmar.mc.worldreplay.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldCreationSessionTest {

    @TempDir
    Path game;
    private final String world = UUID.randomUUID().toString();
    private final UUID profile = UUID.randomUUID();

    private LocalWorldSession acquiring() {
        return new LocalWorldSession(world, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            null, 0, LocalWorldSession.Stage.ACQUIRING, null);
    }

    private String response(boolean hasSave, String base, String committed) {
        return "{\"worldId\":\"" + world + "\",\"sessionId\":\"" + UUID.randomUUID()
            + "\",\"epoch\":9223372036854775806,\"phase\":\"PREPARING\",\"hasSave\":" + hasSave
            + ",\"baseVersion\":" + base + ",\"committedVersion\":" + committed
            + ",\"heartbeatIntervalSeconds\":10,\"heartbeatTimeoutSeconds\":30}";
    }

    /** 缺少服务端明确的有无存档信息或版本矛盾时，不能降级为创建空世界。 */
    @Test
    void ownershipResponseRequiresExplicitConsistentSaveStateAndExactLongs() throws Exception {
        var empty = WorldOwnershipClient.parse(response(false, "null", "null"));
        assertFalse(empty.hasSave());
        assertEquals(9223372036854775806L, empty.epoch());
        assertTrue(WorldOwnershipClient.parse(response(true, "7", "7")).hasSave());
        for (String invalid : new String[]{
            response(false, "null", "null").replace("\"hasSave\":false,", ""),
            response(false, "7", "7"), response(true, "null", "null"), response(true, "6", "7"),
            response(false, "null", "null").replace("9223372036854775806", "1.5"),
            response(false, "null", "null").replace("PREPARING", "FREE"),
            response(false, "null", "null").replace("\"hasSave\":false", "\"hasSave\":\"false\"")}) {
            assertThrows(IOException.class, () -> WorldOwnershipClient.parse(invalid));
        }
    }

    /** 获取请求先落盘，重启后保留同一标识；启动意图落盘后不能取消或重新生成。 */
    @Test
    void sessionRoundTripsAndStartedSessionIsNeverTreatedAsNew() throws Exception {
        var store = new LocalWorldSessionStore(game, "https://relay:443", "房主", profile, world);
        var acquiring = acquiring();
        store.write(acquiring);
        assertEquals(acquiring, store.read());
        var preparing = acquiring.owned(WorldOwnershipClient.parse(response(false, "null", "null")));
        assertTrue(preparing.canOpenCreation());
        assertTrue(preparing.canCancel());
        var requested = preparing.with(LocalWorldSession.Stage.START_REQUESTED, "中文 世界");
        store.write(requested);
        var reopened = new LocalWorldSessionStore(game, "https://relay:443", "房主", profile, world).read();
        assertNotNull(reopened, "测试前置条件：已保存的会话应能重新读取");
        assertEquals(requested, reopened);
        assertFalse(reopened.canCancel());
        assertFalse(reopened.canOpenCreation());
        assertEquals(LocalWorldSessionStore.device(game), LocalWorldSessionStore.device(game));
        assertFalse(Files.exists(game.resolve("saves")));
    }

    /** 同一世界 ID 在不同服务端／账号／任期使用独立目录，旧副本保持原样。 */
    @Test
    void workCopiesAreIsolatedFromVanillaAndOtherScopes() throws Exception {
        var first = new LocalWorldSessionStore(game, "https://first:443", "房主", profile, world);
        var otherServer = new LocalWorldSessionStore(game, "https://second:443", "房主", profile, world);
        var otherProfile = new LocalWorldSessionStore(game, "https://first:443", "房主", UUID.randomUUID(), world);
        Path old = first.saves(1);
        Files.writeString(old.resolve("retained"), "progress");
        assertNotEquals(old, first.saves(3));
        assertNotEquals(old, otherServer.saves(1));
        assertNotEquals(old, otherProfile.saves(1));
        assertEquals("progress", Files.readString(old.resolve("retained")));
        assertTrue(old.startsWith(game.resolve("worldrelay")));
    }

    /** 损坏或缺少必需状态的恢复文件必须阻止重新创建；不能静默重置。 */
    @Test
    void malformedRecoveryRecordFailsClosed() throws Exception {
        var store = new LocalWorldSessionStore(game, "https://relay:443", "房主", profile, world);
        store.write(acquiring());
        Path record;
        try (var paths = Files.walk(game)) {
            record = paths.filter(p -> p.getFileName().toString().equals("session.json")).findFirst().orElseThrow();
        }
        Files.writeString(record, "{\"formatVersion\":1,\"session\":{}}");
        assertThrows(IOException.class, store::read);
        assertThrows(IllegalArgumentException.class, () -> acquiring().with(LocalWorldSession.Stage.PREPARING, "../outside"));
    }

}
