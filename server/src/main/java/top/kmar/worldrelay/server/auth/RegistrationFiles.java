package top.kmar.worldrelay.server.auth;

import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** 两文件注册事务：先持久化备份和恢复标记，最后删除标记才算提交；私钥不进入此层。 */
final class RegistrationFiles implements AutoCloseable {

    final Path users;
    final Path keys;
    final Path pending;
    final Path backups;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Publisher publisher;
    private boolean healthy = true;

    @FunctionalInterface
    interface Publisher {
        void publish(Path temporary, Path target) throws IOException;
    }

    private RegistrationFiles(Path users, Path keys, FileChannel channel, FileLock lock, Publisher publisher) {
        this.users = users;
        this.keys = keys;
        this.lockChannel = channel;
        this.lock = lock;
        this.publisher = publisher;
        this.pending = users.resolveSibling(users.getFileName() + ".registration-pending");
        this.backups = users.resolveSibling(users.getFileName() + ".registration-backups");
    }

    static RegistrationFiles open(Path users, Path keys, Publisher publisher) throws IOException {
        keys = canonicalFile(keys);
        Path keysLock = authorizationLockFile(users, keys);
        FileChannel channel = FileChannel.open(keysLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("SSH authorization file is already in use");
            }
            if (lock == null) throw new IOException("SSH authorization file is already in use");
            var files = new RegistrationFiles(users, keys, channel, lock, publisher);
            files.recover();
            return files;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 校验公钥、用户与事务文件的路径隔离，返回公钥文件对应的锁路径。 */
    private static Path authorizationLockFile(Path users, Path keys) throws IOException {
        Path usersLock = users.resolveSibling(users.getFileName() + ".lock");
        Path keysLock = keys.resolveSibling(keys.getFileName() + ".lock");
        Path pending = users.resolveSibling(users.getFileName() + ".registration-pending");
        Path backups = users.resolveSibling(users.getFileName() + ".registration-backups");
        if (keys.equals(users) || keys.equals(usersLock) || keysLock.equals(users) || keysLock.equals(usersLock)
            || keys.equals(pending) || keysLock.equals(pending) || keys.startsWith(backups)) {
            throw new IOException("SSH authorization and user-store paths must be separate");
        }
        return keysLock;
    }

    static Path canonicalFile(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        path = path.getParent().toRealPath().resolve(path.getFileName());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Registration path must be a regular file: " + path);
        }
        return path;
    }

    static byte[] read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Registration file is not a regular file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return input.readAllBytes();
        }
    }

    static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    boolean isNotHealthy() {
        return !healthy;
    }

    void commit(String username, boolean overwritten, byte[] beforeUsers, byte[] afterUsers, byte[] beforeKeys, byte[] afterKeys)
        throws IOException {
        if (!healthy) throw new IOException("User registration requires recovery");
        requireEqual(read(users), beforeUsers);
        requireEqual(read(keys), beforeKeys);
        Files.createDirectories(backups);
        if (!Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Registration backups must be a real directory");
        JsonWriter writer = new JsonWriter().beginObject().name("version").value(1)
            .name("username").value(username).name("overwritten").value(overwritten)
            .name("authorizedKeysFile").value(keys.toString());
        snapshot(writer, "usersBefore", beforeUsers);
        snapshot(writer, "usersAfter", afterUsers);
        snapshot(writer, "keysBefore", beforeKeys);
        snapshot(writer, "keysAfter", afterKeys);
        byte[] backup = writer.endObject().finish().getBytes(StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        write(backups.resolve(id + ".json"), backup, false);
        byte[] marker = (id + " " + hash(backup) + "\n").getBytes(StandardCharsets.UTF_8);
        write(pending, marker, false);
        try {
            // 先撤销旧 SSH 密钥；新私钥在整个事务提交前不会发给调用方。
            write(keys, afterKeys, true);
            write(users, afterUsers, true);
            Files.delete(pending);
            syncDirectory(pending.getParent());
        } catch (IOException failure) {
            try {
                // 删除提交标记后的目录刷盘若失败，重新建立标记后回滚，不能留下内存/磁盘状态分歧。
                if (read(pending) == null) write(pending, marker, false);
                recover();
            } catch (IOException recoveryFailure) {
                healthy = false;
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        } catch (RuntimeException | Error failure) {
            healthy = false;
            throw failure;
        }
    }

    private void recover() throws IOException {
        byte[] markerBytes = read(pending);
        if (markerBytes == null) return;
        String marker = utf8(markerBytes);
        if (!marker.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} [0-9a-f]{64}\\n")) {
            throw new IOException("Invalid registration recovery marker");
        }
        String id = marker.substring(0, 36);
        if (!UUID.fromString(id).toString().equals(id) || !Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid registration backup path");
        }
        byte[] backup = read(backups.resolve(id + ".json"));
        if (backup == null || !hash(backup).equals(marker.substring(37, 101))) throw new IOException("Registration backup checksum mismatch");
        byte[] beforeUsers, afterUsers, beforeKeys, afterKeys;
        try {
            JsonReader reader = new JsonReader(utf8(backup), Integer.MAX_VALUE, 4);
            reader.beginObject();
            name(reader, "version");
            if (reader.nextInt() != 1) throw new IllegalArgumentException();
            name(reader, "username");
            UserStore.validateUsername(reader.nextString());
            name(reader, "overwritten");
            reader.nextBoolean();
            name(reader, "authorizedKeysFile");
            if (!reader.nextString().equals(keys.toString())) throw new IllegalArgumentException();
            beforeUsers = snapshot(reader, "usersBefore");
            afterUsers = snapshot(reader, "usersAfter");
            beforeKeys = snapshot(reader, "keysBefore");
            afterKeys = snapshot(reader, "keysAfter");
            reader.endObject();
            reader.finish();
            if (afterUsers == null || afterKeys == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid registration backup");
        }
        byte[] currentUsers = read(users), currentKeys = read(keys);
        // 两个文件均须属于已知事务状态；发现外部修改时停止，不能盲目覆盖管理员的修改。
        if (!known(currentUsers, beforeUsers, afterUsers) || !known(currentKeys, beforeKeys, afterKeys)) {
            throw new IOException("Registration files changed outside the pending transaction");
        }
        if (!Arrays.equals(currentUsers, beforeUsers)) write(users, beforeUsers, false);
        if (!Arrays.equals(currentKeys, beforeKeys)) write(keys, beforeKeys, false);
        Files.delete(pending);
        syncDirectory(pending.getParent());
    }

    private void write(Path path, byte[] content, boolean publish) throws IOException {
        if (content == null) {
            Files.deleteIfExists(path);
            syncDirectory(path.getParent());
            return;
        }
        Path temporary = Files.createTempFile(path.getParent(), ".worldrelay-auth-", ".tmp");
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) copyPermissions(path, temporary);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    //noinspection ResultOfMethodCallIgnored
                    output.write(buffer);
                }
                output.force(true);
            }
            if (publish) publisher.publish(temporary, path);
            else atomicMove(temporary, path);
            syncDirectory(path.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyPermissions(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Registration file is not a regular file");
        var posix = Files.getFileAttributeView(source, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var attributes = posix.readAttributes();
            var destination = Files.getFileAttributeView(target, PosixFileAttributeView.class);
            if (!destination.getOwner().equals(attributes.owner())) destination.setOwner(attributes.owner());
            destination.setGroup(attributes.group());
            destination.setPermissions(attributes.permissions());
        }
    }

    static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void syncDirectory(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    private static void snapshot(JsonWriter writer, String name, byte[] bytes) {
        writer.name(name);
        if (bytes == null) writer.nullValue();
        else writer.value(Base64.getEncoder().encodeToString(bytes));
    }

    private static byte[] snapshot(JsonReader reader, String field) {
        name(reader, field);
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return Base64.getDecoder().decode(reader.nextString());
    }

    private static void name(JsonReader reader, String expected) {
        if (!reader.nextName().equals(expected)) throw new IllegalArgumentException();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean known(byte[] current, byte[] before, byte[] after) {
        return Arrays.equals(current, before) || Arrays.equals(current, after);
    }

    private static void requireEqual(byte[] current, byte[] expected) throws IOException {
        if (!Arrays.equals(current, expected)) throw new IOException("Registration file changed outside the service");
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            lockChannel.close();
        }
    }

}
