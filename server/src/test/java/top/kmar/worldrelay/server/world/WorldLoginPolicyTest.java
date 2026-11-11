package top.kmar.worldrelay.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldLoginPolicyTest {
    @TempDir Path root;

    /** 两种登录模式经过取消创建、改名、连续提交、恢复备份及重启后保持创建时的值。 */
    @Test
    void retainsLoginModeThroughoutWorldLifecycle() throws Exception {
        for (boolean offline : new boolean[]{false, true}) {
            Path directory = root.resolve(Boolean.toString(offline));
            String world;
            try (var store = WorldStore.open(directory);
                 var retention = new WorldRetention(store, root.resolve("transfers"),
                     new RetentionConfig(5, 7, 24, 0, 60, ZoneOffset.UTC), Clock.systemUTC(), ignored -> Long.MAX_VALUE)) {
                var created = store.create("模式测试", offline);
                assertNotNull(created);
                world = created.worldId();
                var owner = BackupTestWorld.acquire(store, world);
                assertEquals(offline, store.heartbeat(world, owner, null).allowOfflinePlayers());
                store.cancelCreation(world, owner);
                store.rename(world, "模式测试", "改名测试");
                BackupTestWorld.save(store, world, "first");
                BackupTestWorld.save(store, world, "second");
                var backups = new WorldBackups(store, retention);
                backups.restore(backups.preview(world, 1));
                assertEquals(offline, store.list().getFirst().allowOfflinePlayers());
            }
            try (var store = WorldStore.open(directory)) {
                assertEquals(offline, store.list().getFirst().allowOfflinePlayers());
                var owner = BackupTestWorld.acquire(store, world);
                assertEquals(offline, store.heartbeat(world, owner, null).allowOfflinePlayers());
                var persisted = WorldMetadata.parse(Files.readString(directory.resolve(world).resolve("world.json")));
                assertEquals(offline, persisted.allowOfflinePlayers());
                assertEquals(3L, persisted.state().committedVersion());
            }
        }
    }

    /** 已有世界没有登录模式字段时保留正版验证，并可保存成新格式后再次读取。 */
    @Test
    void loadsExistingWorldWithOnlineAuthentication() {
        var metadata = WorldMetadata.parse("{\"formatVersion\":1,\"worldId\":\"" + UUID.randomUUID()
            + "\",\"name\":\"已有世界\",\"createdAt\":\"2026-11-01T00:00:00Z\"}");
        assertFalse(metadata.allowOfflinePlayers());
        assertEquals(metadata, WorldMetadata.parse(metadata.toJson()));
    }
}
