package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;
import top.kmar.mc.worldreplay.sync.RsyncMode;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalWorldReplacementTest {

    @TempDir
    Path game;

    private final class Fixture implements AutoCloseable {

        final LocalWorldSession previous = new LocalWorldSession(id(), id(), id(), id(), 10,
            LocalWorldSession.Stage.UPLOADING, "旧存档", 7L, id(), "a".repeat(64));
        final String newSession = id();
        final HttpServer api;
        final ClientConnectionSettings settings;
        final LocalWorldSessionStore store;
        final Path oldWorld;
        final AtomicInteger acquireStatus = new AtomicInteger(200);
        final AtomicInteger readyStatus = new AtomicInteger(200);
        final AtomicInteger readyRequests = new AtomicInteger();
        final AtomicBoolean localAllowed = new AtomicBoolean(true);
        final AtomicLong epoch = new AtomicLong(12);
        final AtomicLong version = new AtomicLong(7);
        final AtomicReference<JsonObject> acquired = new AtomicReference<>();
        final AtomicReference<JsonObject> ready = new AtomicReference<>();

        Fixture() throws IOException {
            api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            settings = new ClientConnectionSettings("http://127.0.0.1", api.getAddress().getPort(), "token", RsyncMode.CUSTOM,
                game.resolve("rsync").toString(), game.resolve("ssh").toString());
            store = new LocalWorldSessionStore(game, settings.serverUri().toString(), "Dev", UUID.randomUUID(), previous.worldId());
            store.write(previous);
            oldWorld = Files.createDirectory(store.saves(previous.epoch()).resolve(previous.folder()));
            Files.writeString(oldWorld.resolve(SharedPlayerData.MARKER_FILE), "");
            Files.writeString(oldWorld.resolve("level.dat"), "unuploaded-progress");
            Files.writeString(Files.createDirectory(oldWorld.resolve("mod-data")).resolve("train.dat"), "unuploaded-train");
            api.createContext("/api/v1/worlds/", exchange -> {
                try (exchange) {
                    boolean acquire = exchange.getRequestURI().getPath().endsWith("/acquire");
                    boolean check = exchange.getRequestURI().getPath().endsWith("/check-local-reuse");
                    var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (check) {
                        byte[] response = ("{\"worldId\":\"" + previous.worldId() + "\",\"allowed\":" + localAllowed.get() + "}").getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                        return;
                    }
                    if (acquire) acquired.set(request);
                    else {
                        ready.set(request);
                        readyRequests.incrementAndGet();
                    }
                    int status = acquire ? acquireStatus.get() : readyStatus.get();
                    String error = acquire ? "WORLD_OWNED" : status == 409 ? "STALE_EPOCH" : "WORLD_STATE_UNAVAILABLE";
                    if (acquire && status == 200 && request.has("sourceEpoch") && !localAllowed.get()) {
                        status = 409;
                        error = "LOCAL_REUSE_NOT_ALLOWED";
                    }
                    byte[] response = (status == 200 ? response(acquire ? "PREPARING" : "STARTING")
                        : "{\"code\":\"" + error + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            api.start();
        }

        String response(String phase) {
            return "{\"worldId\":\"" + previous.worldId() + "\",\"sessionId\":\"" + newSession
                + "\",\"epoch\":" + epoch.get() + ",\"phase\":\"" + phase + "\",\"hasSave\":true,\"baseVersion\":" + version.get() + ",\"committedVersion\":" + version.get() + ","
                + "\"heartbeatIntervalSeconds\":5,\"heartbeatTimeoutSeconds\":20}";
        }

        LocalWorldSession acquireLocal() throws IOException {
            var pending = LocalWorldReplacement.select(store, previous, true);
            var view = WorldOwnershipClient.acquire(settings, "Dev", pending);
            var owned = LocalWorldReplacement.acceptOwnership(pending, view).with(LocalWorldSession.Stage.PREPARING, previous.folder());
            store.write(owned);
            return owned;
        }

        @Override
        public void close() {
            api.stop(0);
        }

    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    /** 两种选择都持久化新的获取请求，清除旧任期及上传身份；服务器来源保留旧存档，本地来源保留原目录引用。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistsSelectedSourceAndStartsWithFreshOwnershipIdentity(boolean useLocal) throws Exception {
        try (var fixture = new Fixture()) {
            var pending = LocalWorldReplacement.select(fixture.store, fixture.previous, useLocal);
            assertEquals(pending, fixture.store.read());
            assertEquals(LocalWorldSession.Stage.ACQUIRING, pending.stage());
            assertNotEquals(fixture.previous.requestId(), pending.requestId());
            assertEquals(0, pending.epoch());
            assertNull(pending.sessionId());
            assertNull(pending.uploadId());
            assertNull(pending.manifestHash());
            assertEquals(useLocal, pending.localSource() != null);
            if (useLocal) assertEquals(new LocalWorldSession.LocalSource(10, fixture.previous.folder(), 7L), pending.localSource());
            var view = WorldOwnershipClient.acquire(fixture.settings, "Dev", pending);
            var owned = pending.owned(view);
            fixture.store.write(owned);
            var reloaded = fixture.store.read();
            assertNotNull(reloaded);
            assertEquals(12, reloaded.epoch());
            assertEquals(7L, owned.baseVersion());
            assertEquals(pending.requestId(), fixture.acquired.get().get("requestId").getAsString());
            assertEquals("unuploaded-progress", Files.readString(fixture.oldWorld.resolve("level.dat")));
        }
    }

    /** 明确选择本地进度后，以新基础版本和任期启动；原子移动后重启可重试，正常停服生成新的上传快照。 */
    @Test
    void reusesLocalProgressWithCurrentBaseAndRetriesAfterMove() throws Exception {
        try (var fixture = new Fixture()) {
            var owned = fixture.acquireLocal();
            var prepared = LocalWorldReplacement.prepare(fixture.settings, "Dev", owned, fixture.store);
            Path target = fixture.store.saves(12).resolve(fixture.previous.folder());
            assertEquals(target, prepared.directory());
            assertEquals("unuploaded-progress", Files.readString(target.resolve("level.dat")));
            assertEquals("unuploaded-train", Files.readString(target.resolve("mod-data/train.dat")));
            assertFalse(Files.exists(fixture.oldWorld));
            JsonObject expected = WorldOwnershipClient.identity(owned);
            WorldOwnershipClient.addBase(expected, 7L);
            expected.addProperty("stage", "RESUME");
            assertEquals(expected, fixture.ready.get());
            var reopened = fixture.store.read();
            assertNotNull(reopened);
            assertEquals(owned, reopened);
            assertEquals(prepared, LocalWorldReplacement.prepare(fixture.settings, "Dev", reopened, fixture.store));
            var started = reopened.with(LocalWorldSession.Stage.STARTED, fixture.previous.folder());
            assertNull(started.localSource());
            Files.writeString(target.resolve("level.dat"), "continued-progress");
            var upload = SharedWorldSync.prepareUpload(started.with(LocalWorldSession.Stage.STOPPED, started.folder()), fixture.store);
            assertEquals(12, upload.epoch());
            assertEquals(7L, upload.baseVersion());
            assertNotEquals(fixture.previous.uploadId(), upload.uploadId());
            var payload = fixture.store.transfer(12, "upload");
            SnapshotFiles.verify(payload.resolve("world"), SnapshotManifest.read(payload.resolve("manifest.bin"), upload.manifestHash()));
            assertEquals("continued-progress", Files.readString(payload.resolve("world/level.dat")));
        }
    }

    /** 抢锁输给其他玩家时保留本地进度及选择，不能启动本地世界或使用旧任期登记。 */
    @Test
    void keepsLocalProgressWhenAnotherPlayerAcquiresFirst() throws Exception {
        try (var fixture = new Fixture()) {
            var pending = LocalWorldReplacement.select(fixture.store, fixture.previous, true);
            fixture.acquireStatus.set(409);
            var failure = assertThrows(WorldOwnershipClient.RequestException.class,
                () -> WorldOwnershipClient.acquire(fixture.settings, "Dev", pending));
            assertEquals("WORLD_OWNED", failure.code());
            assertEquals(pending, fixture.store.read());
            assertEquals("unuploaded-progress", Files.readString(fixture.oldWorld.resolve("level.dat")));
            assertEquals(0, fixture.readyRequests.get());
        }
    }

    /** 新任期在启动前被撤销或服务暂不可用时不移动存档，只把明确的身份拒绝视为资格失效。 */
    @ParameterizedTest
    @ValueSource(ints = {409, 503})
    void preservesChosenProgressWhenStartIsNotAccepted(int status) throws Exception {
        try (var fixture = new Fixture()) {
            var owned = fixture.acquireLocal();
            fixture.readyStatus.set(status);
            var failure = assertThrows(WorldOwnershipClient.RequestException.class,
                () -> LocalWorldReplacement.prepare(fixture.settings, "Dev", owned, fixture.store));
            assertEquals(status == 409, SharedWorldRecovery.ownershipLost(failure));
            assertEquals(owned, fixture.store.read());
            assertEquals("unuploaded-progress", Files.readString(fixture.oldWorld.resolve("level.dat")));
            assertFalse(Files.exists(fixture.store.saves(12).resolve(fixture.previous.folder())));
        }
    }

    /** 新任期目标已有另一份世界时不覆盖双方数据，必须在登记新监听前停止恢复。 */
    @Test
    void retainsBothWorldsWhenTargetAlreadyExists() throws Exception {
        try (var fixture = new Fixture()) {
            var owned = fixture.acquireLocal();
            Path target = Files.createDirectory(fixture.store.saves(12).resolve(fixture.previous.folder()));
            Files.writeString(target.resolve("level.dat"), "different-world");
            assertThrows(IOException.class, () -> LocalWorldReplacement.prepare(fixture.settings, "Dev", owned, fixture.store));
            assertEquals("different-world", Files.readString(target.resolve("level.dat")));
            assertEquals("unuploaded-progress", Files.readString(fixture.oldWorld.resolve("level.dat")));
            assertEquals(0, fixture.readyRequests.get());
        }
    }

    /** 选择界面持有的旧记录不能覆盖已落盘的新选择，避免重复点击切换到另一份存档。 */
    @Test
    void rejectsSelectionAgainstAnAlreadyReplacedRecord() throws Exception {
        try (var fixture = new Fixture()) {
            var selected = LocalWorldReplacement.select(fixture.store, fixture.previous, true);
            assertThrows(IOException.class, () -> LocalWorldReplacement.select(fixture.store, fixture.previous, false));
            assertEquals(selected, fixture.store.read());
        }
    }

    /** 预览通过后若发生其他托管，带原任期和基础版本的获取请求仍被拒绝，不能移动旧存档。 */
    @Test
    void rechecksLocalEligibilityWhenAcquiringAfterPreview() throws Exception {
        try (var fixture = new Fixture()) {
            assertTrue(LocalWorldReplacement.allowed(fixture.settings, "Dev", fixture.previous, fixture.store));
            var pending = LocalWorldReplacement.select(fixture.store, fixture.previous, true);
            fixture.localAllowed.set(false);
            assertFalse(LocalWorldReplacement.allowed(fixture.settings, "Dev", pending, fixture.store));
            var failure = assertThrows(WorldOwnershipClient.RequestException.class,
                () -> WorldOwnershipClient.acquire(fixture.settings, "Dev", pending));
            assertEquals("LOCAL_REUSE_NOT_ALLOWED", failure.code());
            assertTrue(LocalWorldReplacement.reuseDenied(failure));
            assertEquals(10, fixture.acquired.get().get("sourceEpoch").getAsLong());
            assertEquals(7, fixture.acquired.get().get("baseVersion").getAsLong());
            assertEquals(pending, fixture.store.read());
            assertEquals("unuploaded-progress", Files.readString(fixture.oldWorld.resolve("level.dat")));
        }
    }

    /** 已取得的任期与本地来源不连续，或服务端版本变化时，保留有效所有权改为从独立目录同步服务器存档。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void switchesToServerDownloadWhenGrantDoesNotMatchLocalSource(boolean versionChanged) throws Exception {
        try (var fixture = new Fixture()) {
            var pending = LocalWorldReplacement.select(fixture.store, fixture.previous, true);
            if (versionChanged) fixture.version.set(8);
            else fixture.epoch.set(14);
            var view = WorldOwnershipClient.acquire(fixture.settings, "Dev", pending);
            var owned = LocalWorldReplacement.acceptOwnership(pending, view);
            fixture.store.write(owned);
            assertEquals(view.epoch(), owned.epoch());
            assertEquals(view.sessionId(), owned.sessionId());
            assertEquals(view.baseVersion(), owned.baseVersion());
            assertEquals(pending.requestId(), owned.requestId());
            assertEquals(LocalWorldSession.Stage.DOWNLOADING, owned.stage());
            assertNull(owned.localSource());
            assertNotEquals(fixture.previous.folder(), owned.folder());
            assertEquals("unuploaded-progress", Files.readString(fixture.oldWorld.resolve("level.dat")));
        }
    }

}