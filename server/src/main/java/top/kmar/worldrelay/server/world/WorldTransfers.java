package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.auth.SshRegistrationConfig;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** SSH 只接触独立传输副本。封存通过复制到私有 inode 完成，不依赖改名或撤销公钥终止旧进程。 */
public final class WorldTransfers {

    private final WorldStore worlds;
    private final UserStore users;
    private final SshRegistrationConfig ssh;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public record Ticket(String transferId, String privateKey, String sshUser, String hostPublicKey, String manifestHash) {

        @Override
        @SuppressWarnings("NullableProblems")
        public String toString() {
            return "Ticket[transferId=" + transferId + ", privateKey=<redacted>]";
        }

    }

    public WorldTransfers(WorldStore worlds, UserStore users, SshRegistrationConfig ssh) {
        this.worlds = worlds;
        this.users = users;
        this.ssh = ssh;
    }

    public void authorize(String worldId, WorldStore.Identity owner, Long base, String id, String hash, boolean upload)
        throws IOException, WorldAccessException {
        if (hash != null && worlds.committedResult(worldId, owner, id, hash, base) != null) return;
        worlds.prepareTransfer(worldId, owner, base, upload);
    }

    public Ticket begin(String worldId, WorldStore.Identity owner, Long base, String uploadId, boolean upload)
        throws IOException, WorldAccessException {
        WorldStore.validateId(uploadId);
        synchronized (locks.computeIfAbsent(worldId, ignored -> new Object())) {
            WorldStore.SnapshotSource source = worlds.prepareTransfer(worldId, owner, base, upload);
            if (!upload && source == null) throw new WorldAccessException(409, "NO_SNAPSHOT", "World has no committed snapshot");
            String hostKey = readHostKey(); // 配置错误时不签发不可用凭据。
            Path directory = transferDirectory(worldId, owner, uploadId, upload);
            Path payload = directory.resolve("payload");
            if (Files.notExists(payload, LinkOption.NOFOLLOW_LINKS)) {
                // 先复制到 SSH 尚无法访问的临时路径；完成后再暴露为该会话的增量基础。
                Path seed = Files.createTempDirectory(directory, ".seed-");
                Path content = seed.resolve("payload");
                if (source == null) {
                    Files.createDirectory(content);
                    Files.createDirectory(content.resolve("world"));
                } else {
                    SnapshotFiles.copyIsolated(source.directory(), content, false);
                    SnapshotManifest manifest = SnapshotManifest.read(content.resolve(SnapshotManifest.FILE_NAME), source.manifestHash());
                    SnapshotFiles.verify(content.resolve("world"), manifest);
                }
                Files.move(content, payload, StandardCopyOption.ATOMIC_MOVE);
                Files.delete(seed);
                SnapshotFiles.forceDirectory(directory);
            }
            SnapshotFiles.requireDirectory(payload);
            // 大型复制期间任期可能改变，签发密钥前再次检查。
            worlds.prepareTransfer(worldId, owner, base, upload);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Transfer preparation interrupted");
            String keyId = keyId(worldId, owner, uploadId, upload);
            return new Ticket(keyId, users.issueTransferKey(owner.username(), keyId, payload, !upload),
                ssh.transferUser(), hostKey, source == null ? null : source.manifestHash());
        }
    }

    public long commit(String worldId, WorldStore.Identity owner, Long base, String uploadId, String hash)
        throws IOException, WorldAccessException {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid manifest hash");
        synchronized (locks.computeIfAbsent(worldId, ignored -> new Object())) {
            Long published = worlds.committedResult(worldId, owner, uploadId, hash, base);
            if (published != null) return published;
            worlds.prepareTransfer(worldId, owner, base, true);
            Path directory = transferDirectory(worldId, owner, uploadId, true);
            Path candidate = worlds.candidateDirectory(worldId, owner).resolve("payload");
            // 旧连接仍可能写入暂存区，私有副本不包含任何共享 inode，也不允许跟随链接。
            SnapshotFiles.copyIsolated(directory.resolve("payload"), candidate, !isWindows());
            try (var entries = Files.list(candidate)) {
                if (!entries.map(p -> p.getFileName().toString()).sorted().toList().equals(java.util.List.of("manifest.bin", "world"))) {
                    throw new IOException("Unexpected files in transfer payload");
                }
            }
            SnapshotManifest manifest = SnapshotManifest.read(candidate.resolve(SnapshotManifest.FILE_NAME), hash);
            if (Files.exists(candidate.resolve("world/session.lock"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Session locks must not be uploaded");
            SnapshotFiles.verify(candidate.resolve("world"), manifest);
            // 所有文件及目录都已 force，原子发布前再次检查身份、任期、基础版本。
            long version = worlds.publishSnapshot(worldId, owner, base, uploadId, hash, candidate);
            try {
                users.revokeTransferKey(keyId(worldId, owner, uploadId, true));
            } catch (IOException failure) {
                // 已完成发布不能因公钥清理失败改报失败；短期密钥仅能写入旧暂存副本并会自动过期。
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Published upload key cleanup failed");
            }
            return version;
        }
    }

    public Long status(String worldId, WorldStore.Identity owner, Long base, String uploadId, String hash) throws WorldAccessException {
        Long version = worlds.committedResult(worldId, owner, uploadId, hash, base);
        if (version == null) worlds.heartbeat(worldId, owner, null);
        return version;
    }

    public void closeDownload(String worldId, WorldStore.Identity owner, String uploadId) throws IOException, WorldAccessException {
        worlds.heartbeat(worldId, owner, null);
        users.revokeTransferKey(keyId(worldId, owner, uploadId, false));
    }

    private Path transferDirectory(String worldId, WorldStore.Identity owner, String id, boolean upload) throws IOException {
        WorldStore.validateId(worldId);
        WorldStore.validateId(id);
        Path root = ssh.transferDirectory().toAbsolutePath().normalize();
        Files.createDirectories(root);
        SnapshotFiles.requireDirectory(root);
        Path path = root.resolve(".sessions")
            .resolve(SnapshotManifest.hash(owner.username().getBytes(StandardCharsets.UTF_8)))
            .resolve(worldId).resolve(Long.toString(owner.epoch())).resolve((upload ? "upload-" : "download-") + id);
        for (Path current = root; !current.equals(path); ) {
            Path relative = current.relativize(path);
            current = current.resolve(relative.getName(0));
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            SnapshotFiles.requireDirectory(current);
        }
        return path;
    }

    private static String keyId(String worldId, WorldStore.Identity owner, String uploadId, boolean upload) {
        return UUID.nameUUIDFromBytes((worldId + ":" + owner.username() + ":" + owner.deviceId() + ":" + owner.sessionId()
            + ":" + owner.epoch() + ":" + uploadId + ":" + upload).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String readHostKey() throws IOException {
        String text;
        try (var input = Files.newInputStream(ssh.hostPublicKeyFile(), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192) throw new IOException("SSH host public key is too large");
            text = new String(bytes, StandardCharsets.US_ASCII).strip();
        }
        String[] parts = text.split("\\s+");
        if (parts.length < 2 || !parts[0].equals("ssh-ed25519") || !parts[1].matches("[A-Za-z0-9+/]+={0,2}")) {
            throw new IOException("Configure an Ed25519 SSH host public key");
        }
        return parts[0] + " " + parts[1];
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

}