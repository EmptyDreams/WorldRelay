package top.kmar.worldrelay.server.auth;

import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
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
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> users = new TreeMap<>();
    private boolean closed;

    private UserStore(Path file, FileChannel lockChannel, FileLock fileLock) {
        this.file = file;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
    }

    public static UserStore open(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        // 解析父目录符号链接，避免同一路径的不同写法绕过旁路锁。
        file = file.getParent().toRealPath().resolve(file.getFileName());
        if (Files.isSymbolicLink(file)) {
            throw new IOException("User store must not be a symbolic link");
        }
        FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
            UserStore store = new UserStore(file, channel, lock);
            store.load();
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 返回原密钥一次；null 表示用户名已存在。失败不会发布到内存。 */
    public synchronized String register(String username) throws IOException {
        ensureOpen();
        validateUsername(username);
        if (users.containsKey(username)) {
            return null;
        }
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        byte[] hash = digest(key);
        Map<String, byte[]> updated = new TreeMap<>(users);
        updated.put(username, hash);
        persist(updated);
        users.put(username, hash);
        return key;
    }

    public synchronized boolean verify(String username, String key) {
        ensureOpen();
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
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            // 只有文件确实不存在才视为首次启动；其他读取错误必须阻止启动。
            return;
        }
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

    private void persist(Map<String, byte[]> updated) throws IOException {
        JsonWriter writer = new JsonWriter().beginObject();
        updated.forEach((username, hash) -> writer.name(username).value(HexFormat.of().formatHex(hash)));
        byte[] json = writer.endObject().finish().getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), ".users-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    //noinspection ResultOfMethodCallIgnored
                    output.write(buffer);
                }
                output.force(true);
            }
            // 不支持原子替换的文件系统直接失败，不降级为可能留下半份文件的覆盖写。
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
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
            fileLock.release();
        } finally {
            lockChannel.close();
        }
    }

}