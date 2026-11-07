package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.kmar.mc.worldreplay.WorldRelay;
import top.kmar.mc.worldreplay.sync.RsyncTools;
import top.kmar.mc.worldreplay.sync.RsyncTransfer;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;
import top.kmar.worldrelay.sync.RsyncCommand;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 下载只能覆盖隔离的下载缓存；上传使用停服后的固定副本，重试复用同一清单与上传 ID。 */
final class SharedWorldSync {

    private SharedWorldSync() {
    }

    static Path download(
        Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store
    ) throws IOException {
        if (session.baseVersion() == null) throw new IOException("No authoritative version");
        Path payload = store.transfer(session.epoch(), "download");
        seedCache(store, payload);
        String id = session.sessionId();
        JsonObject ticket = request(settings, user, session, "download", id, null);
        String expected = ClientJson.requiredString(ticket, "manifestHash");
        boolean verified = false;
        try {
            var cached = SnapshotManifest.read(payload.resolve(SnapshotManifest.FILE_NAME), expected);
            SnapshotFiles.verify(payload.resolve("world"), cached);
            verified = true;
        } catch (IOException ignored) { /* 未下载完成的缓存必须经过 rsync 修复并重新校验。 */ }
        if (!verified) run(game, settings, store, payload, ticket, false);
        SnapshotManifest manifest = SnapshotManifest.read(payload.resolve(SnapshotManifest.FILE_NAME), expected);
        SnapshotFiles.verify(payload.resolve("world"), manifest);
        store.cache(session, "download", expected);
        Path target = store.saves(session.epoch()).resolve(session.folder());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // 只有从未允许 Minecraft 打开的 DOWNLOADING 阶段才允许复用这个已校验的目标。
            SnapshotFiles.verify(target, manifest);
        } else {
            Path temporary = Files.createTempDirectory(store.saves(session.epoch()), ".download-");
            Path copy = temporary.resolve("world");
            SnapshotFiles.copyIsolated(payload.resolve("world"), copy, false);
            SnapshotFiles.verify(copy, manifest);
            Files.move(copy, target, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(temporary);
        }
        try {
            request(settings, user, session, "close-download", id, null);
        } catch (IOException ignored) { /* 只读密钥会过期，不影响已完成的完整性验证。 */ }
        return target;
    }

    static LocalWorldSession prepareUpload(LocalWorldSession session, LocalWorldSessionStore store) throws IOException {
        if (session.stage() == LocalWorldSession.Stage.UPLOADING) return session;
        if (session.stage() != LocalWorldSession.Stage.STOPPED) throw new IOException("World has not stopped safely");
        Path payload = store.transfer(session.epoch(), "upload");
        Path world = payload.resolve("world");
        if (Files.notExists(world, LinkOption.NOFOLLOW_LINKS)) {
            Path temporary = Files.createTempDirectory(payload.getParent(), ".upload-");
            Path copy = temporary.resolve("world");
            SnapshotFiles.copyWorld(store.saves(session.epoch()).resolve(session.folder()), copy);
            Files.move(copy, world, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(temporary);
        }
        SnapshotManifest manifest = SnapshotFiles.scan(world);
        byte[] bytes = manifest.encode();
        // manifest 是二进制，不能经字符串编码写回。
        Path temporary = Files.createTempFile(payload, ".manifest-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    // 缓冲区位置用于继续处理部分写入。
                    //noinspection ResultOfMethodCallIgnored
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, payload.resolve(SnapshotManifest.FILE_NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return session.uploading(UUID.randomUUID().toString(), SnapshotManifest.hash(bytes));
    }

    static void upload(
        Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store
    ) throws IOException {
        Path payload = store.transfer(session.epoch(), "upload");
        // 先查询幂等提交结果；上次成功响应丢失时，不能重新取得锁或创建新上传 ID。
        if (commit(settings, user, session, false)) {
            cacheUploaded(store, session);
            return;
        }
        WorldOwnershipClient.command(settings, user, session, "stop", "BEGIN");
        JsonObject ticket = request(settings, user, session, "upload", session.uploadId(), null);
        SnapshotManifest manifest = SnapshotManifest.read(payload.resolve(SnapshotManifest.FILE_NAME), session.manifestHash());
        SnapshotFiles.verify(payload.resolve("world"), manifest);
        run(game, settings, store, payload, ticket, true);
        commit(settings, user, session, true);
        cacheUploaded(store, session);
    }

    private static void cacheUploaded(LocalWorldSessionStore store, LocalWorldSession session) {
        try {
            store.cache(session, "upload", session.manifestHash());
        } catch (IOException ignored) { /* 缓存索引失败不会改变已确认的权威提交。 */ }
    }

    private static void seedCache(LocalWorldSessionStore store, Path payload) throws IOException {
        if (Files.exists(payload.resolve("world"), LinkOption.NOFOLLOW_LINKS)) return;
        LocalWorldSessionStore.Cache cache;
        try {
            cache = store.cache();
            if (cache == null || cache.payload().equals(payload)) return;
            var manifest = SnapshotManifest.read(cache.payload().resolve(SnapshotManifest.FILE_NAME), cache.hash());
            SnapshotFiles.verify(cache.payload().resolve("world"), manifest);
        } catch (IOException ignored) {
            return;
        } // 失效缓存不影响权威存档，下次从服务器完整拉取。
        Path temporary = Files.createTempDirectory(payload.getParent(), ".cache-seed-");
        Path world = temporary.resolve("world");
        SnapshotFiles.copyIsolated(cache.payload().resolve("world"), world, false);
        Files.move(world, payload.resolve("world"), StandardCopyOption.ATOMIC_MOVE);
        Files.copy(cache.payload().resolve(SnapshotManifest.FILE_NAME), payload.resolve(SnapshotManifest.FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(temporary);
    }

    private static boolean commit(ClientConnectionSettings settings, String user, LocalWorldSession session, boolean required) throws IOException {
        try {
            JsonObject result = request(settings, user, session, required ? "commit" : "commit-status", session.uploadId(), session.manifestHash());
            if (!required && result.get("version").isJsonNull()) return false;
            long version = result.get("version").getAsBigDecimal().longValueExact();
            long expected = session.baseVersion() == null ? 1 : Math.addExact(session.baseVersion(), 1);
            if (version != expected || !session.manifestHash().equals(ClientJson.requiredString(result, "manifestHash"))) {
                throw new IOException("Commit receipt mismatch");
            }
            return true;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid commit receipt");
        }
    }

    private static void run(
        Path game, ClientConnectionSettings settings, LocalWorldSessionStore store, Path payload,
        JsonObject ticket, boolean upload
    ) throws IOException {
        RsyncCommand command;
        try {
            JsonObject json = ticket.getAsJsonObject("rsyncCommand");
            var arguments = new ArrayList<String>();
            for (var argument : json.getAsJsonArray("arguments")) {
                arguments.add(argument.getAsString());
            }
            command = new RsyncCommand(ClientJson.requiredString(json, "host"), json.get("port").getAsBigDecimal().intValueExact(),
                ClientJson.requiredString(json, "user"), arguments);
            if (command.upload() != upload) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid rsync command from server");
        }
        var credentials = new RsyncTransfer.Credentials(ClientJson.requiredString(ticket, "privateKey"), ClientJson.requiredString(ticket, "hostPublicKey"));
        var result = RsyncTransfer.run(RsyncTools.prepare(game, settings.rsyncMode(), settings.rsyncPath(), settings.sshPath()),
            command, credentials, payload, store.temporary());
        WorldRelay.LOGGER.info("World save transfer completed: literalBytes={}, matchedBytes={}", result.literalBytes(), result.matchedBytes());
    }

    private static JsonObject request(
        ClientConnectionSettings settings, String user, LocalWorldSession session,
        String action, String id, String hash
    ) throws IOException {
        JsonObject body = WorldOwnershipClient.identity(session);
        WorldOwnershipClient.addBase(body, session.baseVersion());
        body.addProperty("uploadId", id);
        if (hash != null) body.addProperty("manifestHash", hash);
        long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(2);
        while (true) {
            try {
                JsonObject response = JsonParser.parseString(WorldOwnershipClient.post(settings, user, action, body, false)).getAsJsonObject();
                if (response.has("pending") && response.get("pending").getAsBoolean()) {
                    if (System.nanoTime() > deadline) throw new IOException("Snapshot job timed out");
                    // HTTP 202 的有界后台轮询，不占用渲染或服务器线程。
                    //noinspection BusyWait
                    Thread.sleep(500);
                    continue;
                }
                if (action.equals("close-download")) return response;
                if (!session.worldId().equals(ClientJson.requiredString(response, "worldId"))
                    || response.get("epoch").getAsBigDecimal().longValueExact() != session.epoch()
                    || !id.equals(ClientJson.requiredString(response, "uploadId"))) throw new IOException("Transfer identity mismatch");
                return response;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Snapshot job interrupted");
            } catch (RuntimeException failure) {
                throw new IOException("Invalid transfer response");
            }
        }
    }

}
