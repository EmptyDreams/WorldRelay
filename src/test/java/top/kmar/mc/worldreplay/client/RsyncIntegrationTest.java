package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.sync.RsyncMode;
import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 显式提供隔离测试服务及 SSH 端口后，运行真实的 Windows ↔ Linux rsync/rrsync A→B→A 交接。 */
@EnabledIfEnvironmentVariable(named = "WORLDRELAY_SYNC_TEST_API_PORT", matches = "[0-9]+")
@Timeout(180)
class RsyncIntegrationTest {

    @TempDir
    Path game;

    /** 通过真实 SSH/rsync 验证 A→B→A 增量交接、缓存修复、删除同步及旧副本保留。 */
    @Test
    void handsOverCompleteWorldWithRealSshIncrementalTransfersAndCachedDownload() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String alice = "SyncAlice-" + suffix, bob = "SyncBob-" + suffix;
        int registrationPort = Integer.parseInt(System.getenv("WORLDRELAY_SYNC_TEST_REGISTRATION_PORT"));
        var aliceRegistration = manage(registrationPort, "register", "username", alice);
        var bobRegistration = manage(registrationPort, "register", "username", bob);
        String world = manage(registrationPort, "worlds", "name", "RsyncIntegration-" + suffix).get("worldId").getAsString();
        var settingsA = settings(aliceRegistration.get("token").getAsString());
        var settingsB = settings(bobRegistration.get("token").getAsString());
        UUID profileA = UUID.randomUUID(), profileB = UUID.randomUUID();
        var storeA = new LocalWorldSessionStore(game, settingsA.serverUri().toString(), alice, profileA, world);
        var storeB = new LocalWorldSessionStore(game, settingsB.serverUri().toString(), bob, profileB, world);
        var a = acquire(settingsA, alice, world);
        Path original = Files.createDirectory(storeA.saves(a.epoch()).resolve("world"));
        Files.writeString(original.resolve("level.dat"), "synthetic-level-not-a-playable-world");
        Files.writeString(original.resolve("worldrelay.shared"), "");
        Files.createDirectories(original.resolve("region"));
        byte[] chunks = new byte[4 * 1024 * 1024];
        new SecureRandom().nextBytes(chunks);
        Files.write(original.resolve("region/r.0.0.mca"), chunks);
        Files.createDirectories(original.resolve("模组数据/空目录"));
        Files.writeString(original.resolve("模组数据/列车.dat"), "train-one");
        Files.writeString(original.resolve("delete-on-next-handover.dat"), "obsolete");
        var uploadA = SharedWorldSync.prepareUpload(a.with(LocalWorldSession.Stage.STOPPED, "world"), storeA);
        storeA.write(uploadA);
        SharedWorldSync.upload(game, settingsA, alice, uploadA, storeA);

        var b = acquire(settingsB, bob, world);
        assertEquals(1L, b.baseVersion());
        b = b.with(LocalWorldSession.Stage.DOWNLOADING, "world");
        Path bWorld = SharedWorldSync.download(game, settingsB, bob, b, storeB);
        assertArrayEquals(chunks, Files.readAllBytes(bWorld.resolve("region/r.0.0.mca")));
        assertEquals("train-one", Files.readString(bWorld.resolve("模组数据/列车.dat")));
        assertTrue(Files.isDirectory(bWorld.resolve("模组数据/空目录")));
        // 下载密钥已被撤销；破坏下载缓存后重试，必须签发新密钥并重新修复、校验缓存。
        Files.writeString(storeB.transfer(b.epoch(), "download").resolve("world/level.dat"), "corrupt-cache");
        assertEquals(bWorld, SharedWorldSync.download(game, settingsB, bob, b, storeB));
        for (int index = 1024 * 1024; index < 1024 * 1024 + 4096; index++) {
            chunks[index] ^= 0x5a;
        }
        Files.write(bWorld.resolve("region/r.0.0.mca"), chunks);
        Files.writeString(bWorld.resolve("模组数据/列车.dat"), "train-two");
        Files.delete(bWorld.resolve("delete-on-next-handover.dat"));
        var uploadB = SharedWorldSync.prepareUpload(b.with(LocalWorldSession.Stage.STOPPED, "world"), storeB);
        storeB.write(uploadB);
        SharedWorldSync.upload(game, settingsB, bob, uploadB, storeB);
        // 成功响应丢失后的旧提交重试仍能确认，并且不会重新取得任何所有权。
        SharedWorldSync.upload(game, settingsA, alice, uploadA, storeA);

        var nextA = acquire(settingsA, alice, world).with(LocalWorldSession.Stage.DOWNLOADING, "world");
        assertEquals(2L, nextA.baseVersion());
        Path latest = SharedWorldSync.download(game, settingsA, alice, nextA, storeA);
        assertArrayEquals(chunks, Files.readAllBytes(latest.resolve("region/r.0.0.mca")));
        assertEquals("train-two", Files.readString(latest.resolve("模组数据/列车.dat")));
        assertFalse(Files.exists(latest.resolve("delete-on-next-handover.dat")));
        assertEquals("train-one", Files.readString(original.resolve("模组数据/列车.dat")));
        assertNotEquals(SnapshotFiles.hashFile(original.resolve("region/r.0.0.mca")), SnapshotFiles.hashFile(latest.resolve("region/r.0.0.mca")));
        // 最后从未启动游戏，仅在这个隔离服务中提交已下载的完整快照以完成清理。
        var finalUpload = SharedWorldSync.prepareUpload(nextA.with(LocalWorldSession.Stage.STOPPED, "world"), storeA);
        storeA.write(finalUpload);
        SharedWorldSync.upload(game, settingsA, alice, finalUpload, storeA);
    }

    private ClientConnectionSettings settings(String token) {
        return new ClientConnectionSettings("http://127.0.0.1", Integer.parseInt(System.getenv("WORLDRELAY_SYNC_TEST_API_PORT")), token,
            RsyncMode.BUNDLED, "", "");
    }

    private LocalWorldSession acquire(ClientConnectionSettings settings, String user, String world) throws Exception {
        var request = new LocalWorldSession(world, LocalWorldSessionStore.device(game), UUID.randomUUID().toString(),
            null, 0, LocalWorldSession.Stage.ACQUIRING, null);
        return request.owned(WorldOwnershipClient.acquire(settings, user, request));
    }

    private static JsonObject manage(int port, String route, String key, String value) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(key, value);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/" + route))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }
    }

}
