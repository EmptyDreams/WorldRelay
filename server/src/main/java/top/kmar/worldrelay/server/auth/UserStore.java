package top.kmar.worldrelay.server.auth;

import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** 用户名及高熵密钥摘要的持久存储；进程文件锁和实例锁共同串行化修改。 */
public final class UserStore implements AutoCloseable {

    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final RegistrationFiles files;
    private final SshRegistrationConfig ssh;
    private final boolean mungeSupported;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> users = new TreeMap<>();
    private byte[] usersImage;
    private boolean closed;

    private UserStore(Path file, FileChannel lockChannel, FileLock fileLock, RegistrationFiles files, SshRegistrationConfig ssh) {
        this.file = file;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
        this.files = files;
        this.ssh = ssh;
        this.mungeSupported = RrsyncCapabilities.supportsMunge(ssh.rrsyncCommand());
    }

    public static UserStore open(Path path) throws IOException {
        return open(path, SshRegistrationConfig.defaults(path));
    }

    public static UserStore open(Path path, SshRegistrationConfig ssh) throws IOException {
        return open(path, ssh, RegistrationFiles::atomicMove);
    }

    static UserStore open(Path path, SshRegistrationConfig ssh, RegistrationFiles.Publisher publisher) throws IOException {
        Path file = RegistrationFiles.canonicalFile(path);
        FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        RegistrationFiles files = null;
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("User store is already in use");
            }
            if (lock == null) {
                throw new IOException("User store is already in use");
            }
            files = RegistrationFiles.open(file, ssh.authorizedKeysFile(), publisher);
            UserStore store = new UserStore(file, channel, lock, files, ssh);
            store.load();
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            if (files != null) {
                try {
                    files.close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 返回凭据一次；未明确覆盖时重复用户名返回 null。私钥只存在于返回值中。 */
    public synchronized Registration register(String username, boolean overwrite) throws IOException {
        ensureOpen();
        if (files.isNotHealthy()) throw new IOException("User registration requires recovery");
        validateUsername(username);
        boolean existing = users.containsKey(username);
        if (existing && !overwrite) {
            return null;
        }
        byte[] beforeKeys = RegistrationFiles.read(files.keys);
        String originalKeys = beforeKeys == null ? "" : RegistrationFiles.utf8(beforeKeys);
        // 先检查同名条目，冲突时不生成、替换或返回任何新凭据。
        Path root = ssh.transferDirectory().toAbsolutePath().normalize();
        Path directory = root.resolve(HexFormat.of().formatHex(digest(username)));
        AuthorizedKeys.update(originalKeys, username, "ssh-ed25519 placeholder " + username, ssh.rrsyncCommand(), directory, mungeSupported);
        SshKeyPair pair = SshKeyPair.generate(username, random);
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        byte[] hash = digest(key);
        Map<String, byte[]> updated = new TreeMap<>(users);
        updated.put(username, hash);
        byte[] afterUsers = serialize(updated);
        Files.createDirectories(root);
        root = root.toRealPath();
        directory = root.resolve(directory.getFileName());
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("User transfer directory must be a real directory");
        // 目录可能通过其父目录符号链接改变真实路径，授权命令始终使用最终确认的隔离目录。
        String updatedKeys = AuthorizedKeys.update(originalKeys, username, pair.publicKey(), ssh.rrsyncCommand(), directory, mungeSupported);
        files.commit(username, existing, usersImage, afterUsers, beforeKeys, updatedKeys.getBytes(StandardCharsets.UTF_8));
        users.put(username, hash);
        usersImage = afterUsers;
        return new Registration(username, key, pair.publicKey(), pair.privateKey(), existing);
    }

    public synchronized boolean verify(String username, String key) {
        ensureOpen();
        if (files.isNotHealthy()) return false;
        byte[] expected = users.get(username);
        byte[] actual = digest(key);
        // 未知用户名也计算摘要并进行定长比较；不向调用方区分失败原因。
        boolean matches = MessageDigest.isEqual(expected == null ? new byte[32] : expected, actual);
        return expected != null && matches;
    }

    public static void validateUsername(String username) {
        if (username == null || username.isBlank() || !username.equals(username.strip())
            || username.codePointCount(0, username.length()) > 64
            || username.codePoints().anyMatch(c -> c == ':' || Character.isISOControl(c)
            || Character.getType(c) == Character.SURROGATE)) {
            throw new IllegalArgumentException("Invalid username");
        }
    }

    private void load() throws IOException {
        usersImage = RegistrationFiles.read(file);
        if (usersImage == null) return;
        String json = RegistrationFiles.utf8(usersImage);
        try {
            JsonReader reader = new JsonReader(json, Integer.MAX_VALUE, 4);
            reader.beginObject();
            while (reader.hasNext()) {
                String username = reader.nextName();
                validateUsername(username);
                String hash = reader.nextString();
                if (!hash.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid key digest");
                }
                users.put(username, HexFormat.of().parseHex(hash));
            }
            reader.endObject();
            reader.finish();
        } catch (IllegalArgumentException failure) {
            // 不传播解析器异常，避免损坏文件中的敏感内容进入日志。
            throw new IOException("Invalid user store");
        }
    }

    private static byte[] serialize(Map<String, byte[]> updated) {
        JsonWriter writer = new JsonWriter().beginObject();
        updated.forEach((username, hash) -> writer.name(username).value(HexFormat.of().formatHex(hash)));
        return writer.endObject().finish().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] digest(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("User store is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            files.close();
        } finally {
            try {
                fileLock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    public record Registration(String username, String key, String publicKey, String privateKey, boolean overwritten) {
        @Override
        @SuppressWarnings("NullableProblems")
        public String toString() {
            return "Registration[username=" + username + ", key=<redacted>, privateKey=<redacted>, overwritten=" + overwritten + "]";
        }
    }

    public static final class AuthorizationConflictException extends IOException {
        AuthorizationConflictException() {
            super("Username has an unmanaged or ambiguous authorized_keys entry");
        }
    }

}