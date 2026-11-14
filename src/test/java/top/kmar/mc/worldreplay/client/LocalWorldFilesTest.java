package top.kmar.mc.worldreplay.client;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.DirectoryLock;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.sync.RsyncMode;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LocalWorldFilesTest {

    @TempDir
    Path game;
    private final String world = id();
    private final UUID profile = UUID.randomUUID();

    private static String id() { return UUID.randomUUID().toString(); }

    private LocalWorldSessionStore store(String server, String world) throws IOException {
        return new LocalWorldSessionStore(game, server, "Dev", profile, world);
    }

    private static LocalWorldSessionStore.Cache requireCache(LocalWorldSessionStore store) throws IOException {
        var cache = store.cache();
        assertNotNull(cache, "成功导入或同步后应保留完整缓存");
        return cache;
    }

    private LocalWorldSession session(LocalWorldSession.Stage stage) {
        return new LocalWorldSession(world, id(), id(), id(), 7, stage, "world", 1L, null, null);
    }

    private Path source(String text) throws IOException {
        Path source = Files.createTempDirectory(game, "source-");
        Files.writeString(source.resolve("level.dat"), text);
        Files.writeString(Files.createDirectories(source.resolve("dimensions/example/moon/data")).resolve("地图.dat"), "mod-data");
        Files.writeString(source.resolve("session.lock"), "local-lock");
        Files.createDirectories(source.resolve("empty"));
        return source;
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** 导出的 ZIP 包含完整世界、模组数据和空目录，可重新导入；不会混入传输记录或未完成下载。 */
    @Test
    void exportsCompleteCacheAsImportableZip() throws Exception {
        var store = store("https://relay:443", world);
        LocalWorldFiles.importSnapshot(store, source("export-cache"));
        var cache = requireCache(store);
        Path partial = Files.createDirectory(store.transfer(7, "download").resolve("world"));
        Files.writeString(partial.resolve("level.dat"), "incomplete-download");
        Path archive = game.resolve("exports/共享世界.zip");
        LocalWorldFiles.exportSnapshot(store, archive, false);
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            assertEquals("export-cache", new String(zip.getInputStream(zip.getEntry("level.dat")).readAllBytes(), StandardCharsets.UTF_8));
            assertNotNull(zip.getEntry("dimensions/example/moon/data/地图.dat"));
            assertNotNull(zip.getEntry("empty/"));
            assertNull(zip.getEntry("session.lock"));
            assertNull(zip.getEntry("session.json"));
            assertNull(zip.getEntry("manifest.bin"));
        }
        var imported = store("https://relay:443", id());
        LocalWorldFiles.importSnapshot(imported, archive);
        assertEquals(cache.hash(), requireCache(imported).hash());
        assertEquals(cache, requireCache(store));
        assertNull(store.read());
    }

    /** 有未上传进度或旧任期恢复来源时优先导出工作存档，不退回旧缓存，也不改变恢复状态。 */
    @Test
    void exportsPendingWorkingProgressBeforeOlderCache() throws Exception {
        var store = store("https://relay:443", world);
        LocalWorldFiles.importSnapshot(store, source("older-cache"));
        Path working = store.saves(7).resolve("world");
        SnapshotFiles.copyWorld(source("unuploaded-progress"), working);
        store.write(session(LocalWorldSession.Stage.STOPPED));
        Path archive = game.resolve("working.zip");
        LocalWorldFiles.exportSnapshot(store, archive, false);
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            assertEquals("unuploaded-progress", new String(zip.getInputStream(zip.getEntry("level.dat")).readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(LocalWorldSession.Stage.STOPPED, store.read().stage());
        var replacement = new LocalWorldSession(world, id(), id(), id(), 8, LocalWorldSession.Stage.PREPARING,
            null, 1L, null, null, new LocalWorldSession.LocalSource(7, "world", 1L));
        store.write(replacement);
        Path recovered = game.resolve("recovery.zip");
        LocalWorldFiles.exportSnapshot(store, recovered, false);
        try (var zip = new java.util.zip.ZipFile(recovered.toFile())) {
            assertEquals("unuploaded-progress", new String(zip.getInputStream(zip.getEntry("level.dat")).readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(replacement, store.read());
        Files.delete(working.resolve("level.dat"));
        assertThrows(IOException.class, () -> LocalWorldFiles.exportSnapshot(store, game.resolve("missing-progress.zip"), false));
        assertFalse(Files.exists(game.resolve("missing-progress.zip")));
    }

    /** 覆盖必须显式授权；损坏缓存导出失败时，已有目标 ZIP 和本地数据保持不变。 */
    @Test
    void replacesExportOnlyWithConfirmationAndRetainsDestinationOnFailure() throws Exception {
        var store = store("https://relay:443", world);
        LocalWorldFiles.importSnapshot(store, source("cache"));
        Path archive = game.resolve("existing.zip");
        Files.writeString(archive, "original-file");
        assertThrows(FileAlreadyExistsException.class, () -> LocalWorldFiles.exportSnapshot(store, archive, false));
        assertEquals("original-file", Files.readString(archive));
        LocalWorldFiles.exportSnapshot(store, archive, true);
        byte[] complete = Files.readAllBytes(archive);
        Files.writeString(requireCache(store).payload().resolve("world/level.dat"), "corrupt-cache");
        assertThrows(IOException.class, () -> LocalWorldFiles.exportSnapshot(store, archive, true));
        assertArrayEquals(complete, Files.readAllBytes(archive));
        try (var paths = Files.list(game)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".worldrelay-export-")));
        }
    }

    /** 被 Minecraft 持有的存档不能导出，ZIP 也不能写进受管目录，以免污染或覆盖存档。 */
    @SuppressWarnings("try") // 模拟正在运行的 Minecraft 存档。
    @Test
    void blocksExportOfLiveWorldsAndIntoManagedData() throws Exception {
        var store = store("https://relay:443", world);
        Path working = store.saves(7).resolve("world");
        SnapshotFiles.copyWorld(source("live-world"), working);
        store.write(session(LocalWorldSession.Stage.STOPPED));
        Path archive = game.resolve("locked.zip");
        try (var ignored = DirectoryLock.create(working)) {
            assertThrows(IOException.class, () -> LocalWorldFiles.exportSnapshot(store, archive, false));
        }
        assertFalse(Files.exists(archive));
        assertThrows(IOException.class, () -> LocalWorldFiles.exportSnapshot(store, working.resolve("nested.zip"), false));
        assertFalse(Files.exists(working.resolve("nested.zip")));
    }

    /** 导入只建立缓存，不创建托管会话；新缓存成功后清掉旧缓存，保留源文件、模组数据和空目录。 */
    @Test
    void importsWorldFolderAndReplacesOnlyPreviousCache() throws Exception {
        var store = store("https://relay:443", world);
        LocalWorldFiles.importSnapshot(store, source("old-cache"));
        var previous = requireCache(store);
        Path fresh = source("new-cache");
        LocalWorldFiles.importSnapshot(store, fresh);
        var cache = requireCache(store);
        assertTrue(cache.imported());
        assertNull(store.read());
        assertFalse(Files.exists(previous.payload()));
        assertEquals("new-cache", Files.readString(cache.payload().resolve("world/level.dat")));
        assertEquals("mod-data", Files.readString(cache.payload().resolve("world/dimensions/example/moon/data/地图.dat")));
        assertTrue(Files.isDirectory(cache.payload().resolve("world/empty")));
        assertFalse(Files.exists(cache.payload().resolve("world/session.lock")));
        assertTrue(Files.exists(fresh.resolve("level.dat")));
        SnapshotFiles.verify(cache.payload().resolve("world"), SnapshotManifest.read(cache.payload().resolve("manifest.bin"), cache.hash()));
    }

    /** 已提交会话的旧上传缓存不能覆盖后来手动导入的新缓存。 */
    @Test
    void committedSessionCleanupKeepsNewlyImportedCache() throws Exception {
        var store = store("https://relay:443", world);
        Path payload = store.transfer(7, "upload");
        SnapshotFiles.copyWorld(source("committed"), payload.resolve("world"));
        byte[] manifest = SnapshotFiles.scan(payload.resolve("world")).encode();
        Files.write(payload.resolve("manifest.bin"), manifest);
        var committed = session(LocalWorldSession.Stage.COMMITTED).uploading(id(), SnapshotManifest.hash(manifest))
            .with(LocalWorldSession.Stage.COMMITTED, "world");
        store.write(committed);
        store.cache(committed, "upload", committed.manifestHash());
        LocalWorldFiles.importSnapshot(store, source("imported"));
        store.cleanupAfterCheckpoint();
        assertTrue(requireCache(store).imported());
        assertEquals("imported", Files.readString(requireCache(store).payload().resolve("world/level.dat")));
        assertFalse(Files.exists(payload));
        assertEquals(committed, store.read());
    }

    /** ZIP 支持根目录或一层存档目录；路径穿越、多个世界和链接条目均不能替换已有缓存。 */
    @Test
    void importsZipLayoutsAndRejectsUnsafeOrAmbiguousArchives() throws Exception {
        var store = store("https://relay:443", world);
        for (String prefix : new String[]{"", "存档/"}) {
            Path zip = game.resolve(id() + ".zip");
            try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
                entry(output, prefix + "level.dat", "zip-world");
                entry(output, prefix + "data/地图.dat", "map");
            }
            LocalWorldFiles.importSnapshot(store, zip);
            assertEquals("map", Files.readString(requireCache(store).payload().resolve("world/data/地图.dat")));
        }
        var expected = requireCache(store);
        for (String name : new String[]{"../escape", "/absolute", "other/level.dat"}) {
            Path zip = game.resolve(id() + ".zip");
            try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
                entry(output, "save/level.dat", "valid");
                entry(output, name, "unsafe");
            }
            assertThrows(IOException.class, () -> LocalWorldFiles.importSnapshot(store, zip));
            assertEquals(expected, requireCache(store));
        }
        Path link = game.resolve("link.zip");
        try (var output = new ZipArchiveOutputStream(Files.newOutputStream(link))) {
            var level = new ZipArchiveEntry("level.dat");
            output.putArchiveEntry(level);
            output.write("level".getBytes(StandardCharsets.UTF_8));
            output.closeArchiveEntry();
            var entry = new ZipArchiveEntry("escape");
            entry.setUnixMode(UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
            output.putArchiveEntry(entry);
            output.write("../../outside".getBytes(StandardCharsets.UTF_8));
            output.closeArchiveEntry();
        }
        assertThrows(IOException.class, () -> LocalWorldFiles.importSnapshot(store, link));
        assertEquals(expected, requireCache(store));
        assertFalse(Files.exists(game.resolve("escape")));
    }

    /** 内容与 ZIP 校验码不符时必须拒绝导入，已有缓存仍然完整。 */
    @Test
    void rejectsCorruptZipWithoutLosingPreviousCache() throws Exception {
        var store = store("https://relay:443", world);
        LocalWorldFiles.importSnapshot(store, source("previous"));
        var expected = requireCache(store);
        Path archive = game.resolve("corrupt.zip");
        byte[] data = "unique-zip-level-content".getBytes(StandardCharsets.UTF_8);
        var crc = new CRC32();
        crc.update(data);
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            var entry = new ZipEntry("level.dat");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            output.write(data);
            output.closeEntry();
        }
        byte[] packed = Files.readAllBytes(archive);
        int offset = new String(packed, StandardCharsets.ISO_8859_1).indexOf(new String(data, StandardCharsets.ISO_8859_1));
        assertTrue(offset >= 0);
        packed[offset] ^= 1;
        Files.write(archive, packed);
        assertThrows(IOException.class, () -> LocalWorldFiles.importSnapshot(store, archive));
        assertEquals(expected, requireCache(store));
        assertEquals("previous", Files.readString(expected.payload().resolve("world/level.dat")));
    }

    /** 删除需额外确认未上传进度，确认后彻底清除该世界数据，其他世界和原始导入文件保持不变。 */
    @Test
    void deletesWholeWorldOnlyAfterUnuploadedProgressIsConfirmed() throws Exception {
        var store = store("https://relay:443", world);
        Path original = source("source");
        LocalWorldFiles.importSnapshot(store, original);
        Path working = store.saves(7).resolve("world");
        SnapshotFiles.copyWorld(source("unuploaded"), working);
        Files.writeString(store.temporary().resolve("leftover"), "temporary");
        Files.writeString(store.transfer(7, "upload").resolve("partial"), "upload");
        store.write(session(LocalWorldSession.Stage.STOPPED));
        var other = store("https://relay:443", id());
        Files.writeString(other.root().resolve("untouched"), "other-world");
        assertTrue(LocalWorldFiles.inspect(store.root()).unuploaded());
        assertThrows(IOException.class, () -> LocalWorldFiles.delete(store.root(), false));
        assertEquals("unuploaded", Files.readString(working.resolve("level.dat")));
        LocalWorldFiles.delete(store.root(), true);
        assertFalse(Files.exists(store.root()));
        assertTrue(Files.exists(original.resolve("level.dat")));
        assertEquals("other-world", Files.readString(other.root().resolve("untouched")));
    }

    /** 损坏恢复记录按可能未上传处理；被游戏持有的世界目录禁止删除或导入。 */
    @SuppressWarnings("try") // 目录锁用于模拟运行中的 Minecraft 存档。
    @Test
    void warnsForUnreadableRecoveryAndBlocksLiveWorlds() throws Exception {
        var missing = store("https://relay:443", id());
        Files.writeString(missing.transfer(3, "upload").resolve("partial"), "unconfirmed-upload");
        assertTrue(LocalWorldFiles.inspect(missing.root()).unuploaded());
        assertThrows(IOException.class, () -> LocalWorldFiles.delete(missing.root(), false));
        LocalWorldFiles.delete(missing.root(), true);
        var store = store("https://relay:443", world);
        Files.writeString(store.root().resolve("session.json"), "invalid-json");
        assertTrue(LocalWorldFiles.inspect(store.root()).unuploaded());
        assertThrows(IOException.class, () -> LocalWorldFiles.delete(store.root(), false));
        Path working = store.saves(7).resolve("world");
        SnapshotFiles.copyWorld(source("running"), working);
        Path input = source("new-cache");
        try (var ignored = DirectoryLock.create(working)) {
            assertThrows(IOException.class, () -> LocalWorldFiles.delete(store.root(), true));
            assertThrows(IOException.class, () -> LocalWorldFiles.importSnapshot(store, input));
        }
        LocalWorldFiles.delete(store.root(), true);
        assertFalse(Files.exists(store.root()));
    }

    /** 导入不能淘汰缺少恢复记录或仍待恢复的工作进度，只能替换增量缓存。 */
    @Test
    void importPreservesUnrecordedAndPendingWorkingProgress() throws Exception {
        var store = store("https://relay:443", world);
        Path working = store.saves(6).resolve("world");
        SnapshotFiles.copyWorld(source("unrecorded-progress"), working);
        Path upload = store.transfer(6, "upload").resolve("pending");
        Files.writeString(upload, "unrecorded-upload");
        LocalWorldFiles.importSnapshot(store, source("first-cache"));
        assertEquals("unrecorded-progress", Files.readString(working.resolve("level.dat")));
        assertEquals("unrecorded-upload", Files.readString(upload));
        assertTrue(LocalWorldFiles.inspect(store.root()).unuploaded());
        store.write(session(LocalWorldSession.Stage.DOWNLOADING));
        LocalWorldFiles.importSnapshot(store, source("second-cache"));
        assertEquals("unrecorded-progress", Files.readString(working.resolve("level.dat")));
        assertEquals("unrecorded-upload", Files.readString(upload));
        assertEquals("second-cache", Files.readString(requireCache(store).payload().resolve("world/level.dat")));
    }

    /** 导入的缓存仍需取得服务器下载授权；匹配权威摘要才可复用，过期缓存不能绕过增量同步。 */
    @Test
    void importedCacheStillRequiresServerVerificationBeforeOpening() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var settings = new ClientConnectionSettings("http://127.0.0.1", server.getAddress().getPort(), "token", RsyncMode.CUSTOM,
            game.resolve("missing-rsync").toString(), game.resolve("missing-ssh").toString());
        var store = store(settings.serverUri().toString(), world);
        var session = session(LocalWorldSession.Stage.DOWNLOADING);
        store.write(session);
        Path partial = Files.createDirectory(store.transfer(7, "download").resolve("world"));
        Files.writeString(partial.resolve("level.dat"), "previous-partial-download");
        LocalWorldFiles.importSnapshot(store, source("server-version"));
        var imported = requireCache(store);
        var requests = new AtomicInteger();
        server.createContext("/api/v1/worlds/download", exchange -> {
            byte[] body = ("{\"worldId\":\"" + world + "\",\"epoch\":7,\"uploadId\":\"" + session.sessionId()
                + "\",\"manifestHash\":\"" + imported.hash() + "\"}").getBytes(StandardCharsets.UTF_8);
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/worlds/close-download", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            Path downloaded = SharedWorldSync.download(game, settings, "Dev", session, store);
            assertEquals(1, requests.get());
            assertEquals("server-version", Files.readString(downloaded.resolve("level.dat")));
            assertFalse(requireCache(store).imported());
            assertFalse(Files.exists(imported.payload()));
        } finally {
            server.stop(0);
        }
    }

    /** 旧版本导入不能直接打开；下载重试保留本次已取得的新数据，不反复用旧导入覆盖。 */
    @Test
    void staleImportNeedsTransferAndDoesNotResetPartialDownloadOnRetry() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var settings = new ClientConnectionSettings("http://127.0.0.1", server.getAddress().getPort(), "token", RsyncMode.CUSTOM,
            game.resolve("missing-rsync").toString(), game.resolve("missing-ssh").toString());
        var store = store(settings.serverUri().toString(), world);
        LocalWorldFiles.importSnapshot(store, source("old-version"));
        var imported = requireCache(store);
        var session = session(LocalWorldSession.Stage.DOWNLOADING);
        store.write(session);
        server.createContext("/api/v1/worlds/download", exchange -> {
            var response = new com.google.gson.JsonObject();
            response.addProperty("worldId", world);
            response.addProperty("epoch", 7);
            response.addProperty("uploadId", session.sessionId());
            response.addProperty("manifestHash", "f".repeat(64));
            response.add("rsyncCommand", new com.google.gson.Gson().toJsonTree(
                top.kmar.worldrelay.sync.RsyncCommand.create("127.0.0.1", 22, "relay", false)));
            response.addProperty("privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture\n-----END OPENSSH PRIVATE KEY-----\n");
            response.addProperty("hostPublicKey", "ssh-ed25519 AAAA");
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertThrows(IOException.class, () -> SharedWorldSync.download(game, settings, "Dev", session, store));
            assertFalse(Files.exists(store.saves(7).resolve("world")));
            Path partial = store.transfer(7, "download").resolve("world/level.dat");
            assertEquals("old-version", Files.readString(partial));
            Files.writeString(partial, "new-partial-download");
            assertThrows(IOException.class, () -> SharedWorldSync.download(game, settings, "Dev", session, store));
            assertEquals("new-partial-download", Files.readString(partial));
            assertEquals(imported, requireCache(store));
        } finally {
            server.stop(0);
        }
    }
}
