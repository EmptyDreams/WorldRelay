package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
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

import static top.kmar.mc.worldreplay.client.WorldTransferProgress.Phase.*;

/** 下载只能覆盖隔离的下载缓存；上传和在线检查点均使用固定副本与上传 ID。 */
final class SharedWorldSync {

    private SharedWorldSync() {
    }

    static Path download(
        Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store
    ) throws IOException {
        return download(game, settings, user, session, store, new WorldTransferProgress());
    }

    static Path download(
        Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store, WorldTransferProgress progress
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
        if (!verified) run(game, settings, store, payload, ticket, false, progress);
        progress.phase(VERIFYING);
        SnapshotManifest manifest = SnapshotManifest.read(payload.resolve(SnapshotManifest.FILE_NAME), expected);
        SnapshotFiles.verify(payload.resolve("world"), manifest);
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
        // 工作副本也完整生成后，才能替代失败会话和旧缓存；复制中断不淘汰旧数据。
        store.cache(session, "download", expected);
        store.cleanupAfterCheckpoint();
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
        return session.uploading(UUID.randomUUID().toString(), writeManifest(payload));
    }

    static String writeManifest(Path payload) throws IOException {
        SnapshotManifest manifest = SnapshotFiles.scan(payload.resolve("world"));
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
        return SnapshotManifest.hash(bytes);
    }

    static void upload(
        Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store
    ) throws IOException {
        upload(game, settings, user, session, store, new WorldTransferProgress());
    }

    static void upload(
        Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
        LocalWorldSessionStore store, WorldTransferProgress progress
    ) throws IOException {
        Path payload = store.transfer(session.epoch(), "upload");
        // 先查询幂等提交结果；上次成功响应丢失时，不能重新取得锁或创建新上传 ID。
        if (commitVersion(settings, user, session, "commit-status", session.uploadId(), session.manifestHash()) != null) {
            cacheSnapshot(store, session);
            return;
        }
        WorldOwnershipClient.command(settings, user, session, "stop", "BEGIN");
        transferUpload(game, settings, user, session, store, payload, session.uploadId(), session.manifestHash(), false, progress);
        cacheSnapshot(store, session);
    }

    static LocalWorldSession confirmCheckpoint(ClientConnectionSettings settings, String user, LocalWorldSession session)
        throws IOException {
        var pending = session.checkpoint();
        if (pending == null) return session;
        try {
            Long published = commitVersion(settings, user, session, "checkpoint-status", pending.uploadId(), pending.manifestHash());
            return published == null ? session : session.withCheckpoint(null, published);
        } catch (WorldOwnershipClient.RequestException failure) {
            // 旧协调端缺少检查点路由；退出交接仍会核对它返回的基础版本。
            if (failure.status() == 404 && failure.code().equals("NOT_FOUND")) return session;
            throw failure;
        }
    }

    static LocalWorldSession uploadCheckpoint(Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
                                              LocalWorldSessionStore store, WorldTransferProgress progress) throws IOException {
        var confirmed = confirmCheckpoint(settings, user, session);
        if (confirmed.checkpoint() == null) return confirmed;
        var pending = session.checkpoint();
        Path payload = store.checkpoint(session.epoch(), pending.uploadId());
        long published = transferUpload(game, settings, user, session, store, payload, pending.uploadId(), pending.manifestHash(), true, progress);
        return session.withCheckpoint(null, published);
    }

    private static long transferUpload(Path game, ClientConnectionSettings settings, String user, LocalWorldSession session,
                                       LocalWorldSessionStore store, Path payload, String id, String hash, boolean checkpoint,
                                       WorldTransferProgress progress) throws IOException {
        JsonObject ticket = request(settings, user, session, checkpoint ? "checkpoint-upload" : "upload", id, null);
        SnapshotManifest manifest = SnapshotManifest.read(payload.resolve(SnapshotManifest.FILE_NAME), hash);
        SnapshotFiles.verify(payload.resolve("world"), manifest);
        run(game, settings, store, payload, ticket, true, progress);
        progress.phase(PUBLISHING);
        Long version = commitVersion(settings, user, session, checkpoint ? "checkpoint-commit" : "commit", id, hash);
        if (version == null) throw new IOException("Invalid commit receipt");
        return version;
    }

    static void cacheSnapshot(LocalWorldSessionStore store, LocalWorldSession session) {
        try {
            store.cache(session, "upload", session.manifestHash());
        } catch (IOException ignored) { /* 缓存索引失败不改变上传内容或所有权，仍可用固定快照重试。 */ }
    }

    private static void seedCache(LocalWorldSessionStore store, Path payload) throws IOException {
        LocalWorldSessionStore.Cache cache;
        Path marker = payload.resolveSibling(".import-seed");
        String importedStamp;
        try {
            cache = store.cache();
            if (cache == null || cache.payload().equals(payload)) return;
            boolean existing = Files.exists(payload.resolve("world"), LinkOption.NOFOLLOW_LINKS);
            if (existing && !cache.imported()) return;
            importedStamp = cache.payload().getFileName() + ":" + cache.hash();
            // 手动导入可替换旧的未完成下载；同一次导入只铺底一次，重试保留已经下载的新数据。
            if (existing && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && Files.size(marker) <= 256 && Files.readString(marker).equals(importedStamp)) return;
            var manifest = SnapshotManifest.read(cache.payload().resolve(SnapshotManifest.FILE_NAME), cache.hash());
            SnapshotFiles.verify(cache.payload().resolve("world"), manifest);
        } catch (IOException ignored) {
            return;
        } // 失效缓存不影响权威存档，下次从服务器完整拉取。
        Path temporary = Files.createTempDirectory(payload.getParent(), ".cache-seed-");
        Path world = temporary.resolve("world");
        SnapshotFiles.copyIsolated(cache.payload().resolve("world"), world, false);
        if (Files.exists(payload.resolve("world"), LinkOption.NOFOLLOW_LINKS)) {
            Files.move(payload.resolve("world"), temporary.resolve("previous"), StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(world, payload.resolve("world"), StandardCopyOption.ATOMIC_MOVE);
        Files.copy(cache.payload().resolve(SnapshotManifest.FILE_NAME), payload.resolve(SnapshotManifest.FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        if (cache.imported()) LocalWorldSessionStore.atomicWrite(marker, importedStamp);
        LocalWorldCleanup.deleteTree(temporary);
    }

    private static @Nullable Long commitVersion(ClientConnectionSettings settings, String user, LocalWorldSession session,
                                                String action, String id, String hash) throws IOException {
        try {
            JsonObject result = request(settings, user, session, action, id, hash);
            if (result.get("version").isJsonNull()) return null;
            long version = result.get("version").getAsBigDecimal().longValueExact();
            if (version != session.nextVersion() || !hash.equals(ClientJson.requiredString(result, "manifestHash"))) {
                throw new IOException("Commit receipt mismatch");
            }
            return version;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid commit receipt");
        }
    }

    private static void run(
        Path game, ClientConnectionSettings settings, LocalWorldSessionStore store, Path payload,
        JsonObject ticket, boolean upload, WorldTransferProgress progress
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
        var tools = RsyncTools.prepare(game, settings.rsyncMode(), settings.rsyncPath(), settings.sshPath());
        progress.phase(TRANSFERRING);
        var result = RsyncTransfer.run(tools, command, credentials, payload, store.temporary(), progress::update);
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