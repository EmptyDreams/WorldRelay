package top.kmar.worldrelay.server.auth;

import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 用户名及高熵密钥摘要的持久存储；进程文件锁和实例锁共同串行化修改。 */
public final class UserStore implements AutoCloseable {

    private static final Duration TRANSFER_KEY_LIFETIME = Duration.ofHours(1);

    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final RegistrationFiles files;
    private final SshRegistrationConfig ssh;
    private final boolean mungeSupported;
    private final OpenSshCapabilities.ExpiryTime expiryTime;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> users = new TreeMap<>();
    private final Set<String> disabled = new HashSet<>();
    private byte[] usersImage;
    private final Map<String, Instant> transferKeyExpiry = new HashMap<>();
    private final Map<String, String> transferKeyUsers = new HashMap<>();
    private final Map<String, String> transferKeyWorlds = new HashMap<>();
    private ScheduledExecutorService keyExpiryWorker;
    private boolean closed;

    private UserStore(
        Path file, FileChannel lockChannel, FileLock fileLock, RegistrationFiles files, SshRegistrationConfig ssh,
        java.util.function.Predicate<String> capabilities, OpenSshCapabilities.ExpiryTime expiryTime
    ) {
        this.file = file;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
        this.files = files;
        this.ssh = ssh;
        this.mungeSupported = capabilities.test(ssh.rrsyncCommand());
        this.expiryTime = expiryTime;
    }

    public static UserStore open(Path path) throws IOException {
        return open(path, SshRegistrationConfig.defaults(path));
    }

    public static UserStore open(Path path, SshRegistrationConfig ssh) throws IOException {
        return open(path, ssh, RegistrationFiles::atomicMove);
    }

    static UserStore open(Path path, SshRegistrationConfig ssh, RegistrationFiles.Publisher publisher) throws IOException {
        return open(path, ssh, publisher, RrsyncCapabilities::supportsMunge, OpenSshCapabilities.detect());
    }

    static UserStore open(
        Path path, SshRegistrationConfig ssh, RegistrationFiles.Publisher publisher,
        java.util.function.Predicate<String> capabilities, OpenSshCapabilities.ExpiryTime expiryTime
    ) throws IOException {
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
            UserStore store = new UserStore(file, channel, lock, files, ssh, capabilities, expiryTime);
            store.load();
            store.clearTransferKeys(); // 重启后不再信任上个进程签发的临时传输凭据。
            store.hardenRegisteredKeys();
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

    /** 只返回本次生成的 API Token；注册不生成 SSH 密钥、改写授权或创建传输目录。 */
    public synchronized Registration register(String username, boolean overwrite) throws IOException {
        ensureOpen();
        if (files.isNotHealthy()) throw new IOException("User registration requires recovery");
        validateUsername(username);
        boolean existing = users.containsKey(username);
        if (existing && !overwrite) {
            return null;
        }
        byte[] beforeKeys = RegistrationFiles.read(files.keys);
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        byte[] hash = digest(key);
        Map<String, byte[]> updated = new TreeMap<>(users);
        updated.put(username, hash);
        byte[] afterUsers = serialize(updated, disabled);
        files.commit(username, existing, usersImage, afterUsers, beforeKeys, beforeKeys);
        users.put(username, hash);
        usersImage = afterUsers;
        return new Registration(username, key, existing);
    }

    public synchronized boolean verify(String username, String key) {
        ensureOpen();
        if (files.isNotHealthy()) return false;
        byte[] expected = users.get(username);
        byte[] actual = digest(key);
        // 未知用户名也计算摘要并进行定长比较；不向调用方区分失败原因。
        boolean matches = MessageDigest.isEqual(expected == null ? new byte[32] : expected, actual);
        return expected != null && matches && !disabled.contains(username);
    }

    public record Account(String username, boolean enabled, long transferAuthorizations) {

    }

    public synchronized List<Account> list() {
        ensureOpen();
        return users.keySet().stream().map(this::account).toList();
    }

    private Account account(String username) {
        return new Account(username, !disabled.contains(username),
            transferKeyUsers.values().stream().filter(username::equals).count());
    }

    public synchronized boolean isEnabled(String username) {
        return !closed && !files.isNotHealthy() && users.containsKey(username) && !disabled.contains(username);
    }

    /** 用户状态与临时公钥在同一恢复事务中发布；不改变任何世界的所有权。 */
    public synchronized Account setEnabled(String username, boolean enabled) throws IOException {
        ensureOpen();
        validateUsername(username);
        if (!users.containsKey(username)) return null;
        if (files.isNotHealthy()) throw new IOException("User management requires recovery");
        var nextDisabled = new HashSet<>(disabled);
        if (enabled) nextDisabled.remove(username);
        else nextDisabled.add(username);
        byte[] beforeKeys = RegistrationFiles.read(files.keys);
        byte[] afterKeys = beforeKeys;
        List<String> revoked = enabled ? List.of() : transferKeyUsers.entrySet().stream()
            .filter(entry -> entry.getValue().equals(username)).map(Map.Entry::getKey).toList();
        if (beforeKeys != null && !revoked.isEmpty()) {
            String text = RegistrationFiles.utf8(beforeKeys);
            for (String id : revoked) {
                text = removeTransferKey(text, "worldrelay-transfer:" + id);
            }
            afterKeys = text.getBytes(StandardCharsets.UTF_8);
        }
        byte[] afterUsers = serialize(users, nextDisabled);
        if (!Arrays.equals(usersImage, afterUsers) || !Arrays.equals(beforeKeys, afterKeys)) {
            files.commit(username, true, usersImage, afterUsers, beforeKeys, afterKeys);
            usersImage = afterUsers;
        }
        disabled.clear();
        disabled.addAll(nextDisabled);
        for (String id : revoked) {
            forgetTransfer(id);
        }
        return account(username);
    }

    /** HTTP 已验证持有者后签发目录专用的短期密钥，不授予权威目录访问权。 */
    public synchronized String issueTransferKey(String username, String transferId, Path directory, boolean readOnly) throws IOException {
        return issueTransferKey(username, transferId, directory, readOnly, null);
    }

    public synchronized String issueTransferKey(String username, String transferId, Path directory, boolean readOnly, String worldId) throws IOException {
        ensureOpen();
        if (!isEnabled(username)) throw new IOException("Transfer user is unavailable");
        if (!java.util.UUID.fromString(transferId).toString().equals(transferId)) throw new IllegalArgumentException("Invalid transfer ID");
        Path sessions = ssh.transferDirectory().toAbsolutePath().normalize().resolve(".sessions");
        Path canonical = directory.toRealPath();
        if (!canonical.startsWith(sessions) || !canonical.equals(directory.toAbsolutePath().normalize())) {
            throw new IOException("Invalid SSH transfer root");
        }
        String comment = "worldrelay-transfer:" + transferId; // ':' 不能出现在注册用户名中。
        SshKeyPair pair = SshKeyPair.generate(comment, random);
        byte[] before = RegistrationFiles.read(files.keys);
        String original = before == null ? "" : RegistrationFiles.utf8(before);
        String retained = removeTransferKey(original, comment);
        if (retained.lines().filter(line -> line.contains(" worldrelay-transfer:")).count() >= 1024) {
            throw new IOException("Too many SSH transfer authorizations");
        }
        String command = shellQuote(ssh.rrsyncCommand()) + (readOnly ? " -ro " : " -wo ")
            + (mungeSupported ? "-munge " : "") + shellQuote(canonical.toString());
        Instant expires = Instant.now().plus(TRANSFER_KEY_LIFETIME).truncatedTo(ChronoUnit.SECONDS);
        String expiryOption = "";
        if (expiryTime == OpenSshCapabilities.ExpiryTime.UNSUPPORTED) {
            // 旧版 sshd 由强制命令检查时间，Java 停服后仍不能用过期密钥发起传输。
            String checked = "worldrelay_now=$(/bin/date +%s) || exit 1; [ \"$worldrelay_now\" -lt " + expires.getEpochSecond()
                + " ] || exit 1; exec " + command;
            command = "/bin/sh -c " + shellQuote(checked);
        } else {
            expiryOption = "expiry-time=\"" + expiryTime.format(expires) + "\",";
        }
        String line = "restrict," + expiryOption + "command=\""
            // OpenSSH 的 authorized_keys 只反转义双引号，不能将普通反斜杠加倍。
            + command.replace("\"", "\\\"") + "\" " + pair.publicKey() + "\n";
        if (line.getBytes(StandardCharsets.UTF_8).length > 8192) throw new IOException("SSH transfer authorization is too long");
        files.replaceKeys(before, (retained + (retained.isEmpty() || retained.endsWith("\n") ? "" : "\n") + line).getBytes(StandardCharsets.UTF_8));
        transferKeyExpiry.put(transferId, expires);
        transferKeyUsers.put(transferId, username);
        if (worldId != null) transferKeyWorlds.put(transferId, worldId);
        if (keyExpiryWorker == null) {
            keyExpiryWorker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "worldrelay-transfer-key-expiry");
                thread.setDaemon(true);
                return thread;
            });
            keyExpiryWorker.scheduleWithFixedDelay(() -> {
                try {
                    expireTransferKeys(Instant.now());
                } catch (IOException failure) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Expired transfer authorization cleanup failed");
                }
            }, 15, 15, TimeUnit.SECONDS);
        }
        return pair.privateKey();
    }

    public synchronized void revokeTransferKey(String transferId) throws IOException {
        ensureOpen();
        if (!java.util.UUID.fromString(transferId).toString().equals(transferId)) throw new IllegalArgumentException("Invalid transfer ID");
        byte[] before = RegistrationFiles.read(files.keys);
        if (before == null) {
            forgetTransfer(transferId);
            return;
        }
        String original = RegistrationFiles.utf8(before);
        String updated = removeTransferKey(original, "worldrelay-transfer:" + transferId);
        if (!original.equals(updated)) files.replaceKeys(before, updated.getBytes(StandardCharsets.UTF_8));
        forgetTransfer(transferId);
    }

    private void forgetTransfer(String id) {
        transferKeyExpiry.remove(id);
        transferKeyUsers.remove(id);
        transferKeyWorlds.remove(id);
    }

    public synchronized void revokeWorldTransfers(String worldId) throws IOException {
        for (String id : transferKeyWorlds.entrySet().stream().filter(entry -> entry.getValue().equals(worldId))
            .map(Map.Entry::getKey).toList()) {
            revokeTransferKey(id);
        }
    }

    synchronized void expireTransferKeys(Instant now) throws IOException {
        if (closed) return;
        for (String id : java.util.List.copyOf(transferKeyExpiry.keySet())) {
            if (!now.isBefore(transferKeyExpiry.get(id))) revokeTransferKey(id);
        }
    }

    private void clearTransferKeys() throws IOException {
        byte[] before = RegistrationFiles.read(files.keys);
        if (before == null) return;
        String original = RegistrationFiles.utf8(before);
        StringBuilder retained = new StringBuilder();
        for (String line : original.split("(?<=\\n)", -1)) {
            if (!line.stripTrailing().matches(".* worldrelay-transfer:[0-9a-f-]{36}")) retained.append(line);
        }
        if (!original.contentEquals(retained)) files.replaceKeys(before, retained.toString().getBytes(StandardCharsets.UTF_8));
        transferKeyExpiry.clear();
        transferKeyUsers.clear();
        transferKeyWorlds.clear();
    }

    private void hardenRegisteredKeys() throws IOException {
        if (!mungeSupported || users.isEmpty()) return;
        byte[] before = RegistrationFiles.read(files.keys);
        if (before == null) return;
        String original = RegistrationFiles.utf8(before);
        String hardened = AuthorizedKeys.harden(original, users.keySet(), ssh.rrsyncCommand());
        if (!original.equals(hardened)) files.replaceKeys(before, hardened.getBytes(StandardCharsets.UTF_8));
    }

    private static String removeTransferKey(String original, String comment) {
        StringBuilder retained = new StringBuilder();
        for (String line : original.split("(?<=\\n)", -1)) {
            if (!line.stripTrailing().endsWith(" " + comment)) retained.append(line);
        }
        return retained.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
                String hash;
                if (reader.peek() == JsonToken.STRING) hash = reader.nextString();
                else {
                    reader.beginObject();
                    if (!reader.nextName().equals("tokenHash")) throw new IllegalArgumentException();
                    hash = reader.nextString();
                    if (!reader.nextName().equals("enabled")) throw new IllegalArgumentException();
                    if (!reader.nextBoolean()) disabled.add(username);
                    reader.endObject();
                }
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

    private static byte[] serialize(Map<String, byte[]> updated, Set<String> disabled) {
        JsonWriter writer = new JsonWriter().beginObject();
        updated.forEach((username, hash) -> {
            writer.name(username);
            if (disabled.contains(username)) writer.beginObject().name("tokenHash").value(HexFormat.of().formatHex(hash))
                .name("enabled").value(false).endObject();
            else writer.value(HexFormat.of().formatHex(hash));
        });
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
        if (keyExpiryWorker != null) keyExpiryWorker.shutdownNow();
        try {
            try {
                clearTransferKeys();
            } finally {
                files.close();
            }
        } finally {
            try {
                fileLock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    public record Registration(String username, String token, boolean overwritten) {

        @Override
        @SuppressWarnings("NullableProblems")
        public String toString() {
            return "Registration[username=" + username + ", token=<redacted>, overwritten=" + overwritten + "]";
        }

    }

}