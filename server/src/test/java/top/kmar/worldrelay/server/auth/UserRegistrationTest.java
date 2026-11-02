package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UserRegistrationTest {

    @TempDir
    Path directory;

    /** 验证重复注册无副作用，只有显式覆盖才同时更换 Token、SSH 密钥；旧凭据在重启后仍被拒绝。 */
    @Test
    void explicitOverwriteResetsBothCredentialsAndKeepsBackups() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        UserStore.Registration first, second;
        try (var users = UserStore.open(file, ssh)) {
            first = users.register("Dev", false);
            assertNotNull(first, "测试前置条件：首次注册应成功");
            byte[] originalKeys = Files.readAllBytes(ssh.authorizedKeysFile());
            assertNull(users.register("Dev", false));
            assertArrayEquals(originalKeys, Files.readAllBytes(ssh.authorizedKeysFile()));
            second = users.register("Dev", true);
            assertNotNull(second, "显式覆盖应返回新的注册凭据");
            assertTrue(second.overwritten());
            assertNotEquals(first.key(), second.key());
            assertNotEquals(first.publicKey(), second.publicKey());
            assertNotEquals(first.privateKey(), second.privateKey());
            assertFalse(users.verify("Dev", first.key()));
            assertTrue(users.verify("Dev", second.key()));
            String authorized = Files.readString(ssh.authorizedKeysFile());
            assertFalse(authorized.contains(first.publicKey()));
            assertTrue(authorized.contains(second.publicKey()));
            assertTrue(authorized.startsWith("restrict,command=\""));
        }
        try (var users = UserStore.open(file, ssh)) {
            assertFalse(users.verify("Dev", first.key()));
            assertTrue(users.verify("Dev", second.key()));
        }
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path);
                assertFalse(content.contains(first.key()));
                assertFalse(content.contains(second.key()));
                assertFalse(content.contains("BEGIN OPENSSH PRIVATE KEY"));
            }
        }
        try (var backups = Files.list(directory.resolve("users.json.registration-backups"))) {
            assertEquals(2, backups.count());
        }
        assertFalse(second.toString().contains(second.key()));
        assertFalse(second.toString().contains(second.privateKey()));
    }

    /** 验证 JSON 缺少用户但公钥文件有同名 rrsync 行时直接替换，保留其他用户和 CRLF 文本，不追加重复密钥。 */
    @Test
    void replacesOrphanedRrsyncEntryWithoutRewritingUnrelatedLines() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        Files.createDirectories(ssh.authorizedKeysFile().getParent());
        String prefix = "# manual keys\r\nssh-ed25519 AAAA Admin\r\n";
        Files.writeString(ssh.authorizedKeysFile(), prefix + "command=\"rrsync -ro '/old root'\" ssh-rsa AAAA Dev\r\n");
        try (var users = UserStore.open(file, ssh)) {
            var registration = users.register("Dev", false);
            assertNotNull(registration, "孤立的 rrsync 授权行应允许重新注册用户");
            assertFalse(registration.overwritten());
            assertTrue(users.verify("Dev", registration.key()));
            String authorized = Files.readString(ssh.authorizedKeysFile());
            assertTrue(authorized.startsWith(prefix));
            assertTrue(authorized.endsWith(registration.publicKey() + "\r\n"));
            assertEquals(1, authorized.lines().filter(line -> line.endsWith(" Dev")).count());
            assertFalse(authorized.contains("/old root"));
        }
    }

    /** 验证同名普通 SSH 行始终受保护；即使传入覆盖参数，也不能创建 JSON 用户或改写已有授权。 */
    @Test
    void unmanagedEntryIsNeverOverwritten() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        Files.createDirectories(ssh.authorizedKeysFile().getParent());
        String original = "ssh-ed25519 AAAA Dev\n";
        Files.writeString(ssh.authorizedKeysFile(), original);
        try (var users = UserStore.open(file, ssh)) {
            for (boolean overwrite : new boolean[]{false, true}) {
                assertThrows(UserStore.AuthorizationConflictException.class, () -> users.register("Dev", overwrite));
                assertFalse(Files.exists(file));
                assertEquals(original, Files.readString(ssh.authorizedKeysFile()));
            }
        }
    }

    /** 验证 JSON 已有用户时仍不能覆盖管理员后来放入的普通 SSH 行，原 Token 和两个文件均保持有效。 */
    @Test
    void unmanagedCollisionDoesNotResetExistingJsonUser() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        try (var users = UserStore.open(file, ssh)) {
            var registration = users.register("Dev", false);
            assertNotNull(registration, "测试前置条件：被保护的原用户应注册成功");
            byte[] before = Files.readAllBytes(file);
            Files.writeString(ssh.authorizedKeysFile(), "ssh-ed25519 AAAA Dev\n");
            assertThrows(UserStore.AuthorizationConflictException.class, () -> users.register("Dev", true));
            assertTrue(users.verify("Dev", registration.key()));
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals("ssh-ed25519 AAAA Dev\n", Files.readString(ssh.authorizedKeysFile()));
        }
    }

    /** 验证第二个文件发布失败会回滚已替换的公钥，保留旧凭据，并允许故障解除后重新覆盖。 */
    @Test
    void secondPublicationFailureRollsBackBothFilesAndCanBeRetried() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        AtomicBoolean fail = new AtomicBoolean();
        try (var users = UserStore.open(file, ssh, (temporary, target) -> {
            if (fail.get() && target.getFileName().equals(file.getFileName())) throw new IOException("injected publication failure");
            RegistrationFiles.atomicMove(temporary, target);
        })) {
            var original = users.register("Dev", false);
            assertNotNull(original, "测试前置条件：故障注入前应注册成功");
            byte[] originalUsers = Files.readAllBytes(file), originalKeys = Files.readAllBytes(ssh.authorizedKeysFile());
            fail.set(true);
            assertThrows(IOException.class, () -> users.register("Dev", true));
            assertArrayEquals(originalUsers, Files.readAllBytes(file));
            assertArrayEquals(originalKeys, Files.readAllBytes(ssh.authorizedKeysFile()));
            assertTrue(users.verify("Dev", original.key()));
            assertFalse(Files.exists(directory.resolve("users.json.registration-pending")));
            fail.set(false);
            var retried = users.register("Dev", true);
            assertNotNull(retried, "排除发布故障后应允许覆盖注册");
            assertTrue(retried.overwritten());
        }
    }

    /** 验证在任一文件替换后突然中断，当前实例停止接受凭据；重启先恢复原文件，再允许认证和注册。 */
    @Test
    void interruptedTransactionIsRecoveredBeforeOpeningStore() throws Exception {
        for (boolean crashAfterUsers : new boolean[]{false, true}) {
            Path root = Files.createDirectory(directory.resolve(crashAfterUsers ? "after-users" : "after-keys"));
            Path file = root.resolve("users.json");
            var ssh = SshRegistrationConfig.defaults(file);
            AtomicBoolean crash = new AtomicBoolean();
            UserStore.Registration original;
            byte[] beforeUsers, beforeKeys;
            try (var users = UserStore.open(file, ssh, (temporary, target) -> {
                RegistrationFiles.atomicMove(temporary, target);
                if (crash.get() && target.getFileName().equals((crashAfterUsers ? file : ssh.authorizedKeysFile()).getFileName())) {
                    throw new SimulatedCrash();
                }
            })) {
                original = users.register("Dev", false);
                assertNotNull(original, "测试前置条件：模拟中断前应注册成功");
                beforeUsers = Files.readAllBytes(file);
                beforeKeys = Files.readAllBytes(ssh.authorizedKeysFile());
                crash.set(true);
                assertThrows(SimulatedCrash.class, () -> users.register("Dev", true));
                assertFalse(users.verify("Dev", original.key()));
                assertThrows(IOException.class, () -> users.register("another", false));
            }
            assertTrue(Files.exists(root.resolve("users.json.registration-pending")));
            try (var recovered = UserStore.open(file, ssh)) {
                assertArrayEquals(beforeUsers, Files.readAllBytes(file));
                assertArrayEquals(beforeKeys, Files.readAllBytes(ssh.authorizedKeysFile()));
                assertTrue(recovered.verify("Dev", original.key()));
                assertNotNull(recovered.register("another", false));
            }
        }
    }

    /** 验证待恢复事务之外的人工修改不能被回滚覆盖；服务拒绝启动并保留现场及备份供管理员处理。 */
    @Test
    void recoveryRefusesUnrecognizedExternalChanges() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        try (var users = UserStore.open(file, ssh, (temporary, target) -> {
            RegistrationFiles.atomicMove(temporary, target);
            if (target.getFileName().equals(ssh.authorizedKeysFile().getFileName())) throw new SimulatedCrash();
        })) {
            assertThrows(SimulatedCrash.class, () -> users.register("Dev", false));
        }
        String external = Files.readString(ssh.authorizedKeysFile()) + "ssh-ed25519 AAAA Admin\n";
        Files.writeString(ssh.authorizedKeysFile(), external);
        assertOpenRejected(file, ssh);
        assertEquals(external, Files.readString(ssh.authorizedKeysFile()));
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(directory.resolve("users.json.registration-pending")));
    }

    /** 验证恢复备份被修改后不能用于回滚，服务拒绝启动且不覆盖事务现场的授权文件。 */
    @Test
    void recoveryRejectsCorruptedBackupWithoutChangingFiles() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        try (var users = UserStore.open(file, ssh, (temporary, target) -> {
            RegistrationFiles.atomicMove(temporary, target);
            throw new SimulatedCrash();
        })) {
            assertThrows(SimulatedCrash.class, () -> users.register("Dev", false));
        }
        byte[] beforeKeys = Files.readAllBytes(ssh.authorizedKeysFile());
        try (var backups = Files.list(directory.resolve("users.json.registration-backups"))) {
            Files.writeString(backups.findFirst().orElseThrow(), "{}");
        }
        assertOpenRejected(file, ssh);
        assertArrayEquals(beforeKeys, Files.readAllBytes(ssh.authorizedKeysFile()));
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(directory.resolve("users.json.registration-pending")));
    }

    /** 验证 Linux 原子替换公钥文件时保留属主、组和权限，避免注册破坏 sshd 对授权文件的权限要求。 */
    @Test
    void preservesPosixAuthorizationOwnershipAndPermissions() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        Path file = directory.resolve("users.json");
        var ssh = SshRegistrationConfig.defaults(file);
        try (var users = UserStore.open(file, ssh)) {
            users.register("Dev", false);
            Files.setPosixFilePermissions(ssh.authorizedKeysFile(), PosixFilePermissions.fromString("rw-r-----"));
            var before = Files.getFileAttributeView(ssh.authorizedKeysFile(), PosixFileAttributeView.class).readAttributes();
            users.register("Dev", true);
            var after = Files.getFileAttributeView(ssh.authorizedKeysFile(), PosixFileAttributeView.class).readAttributes();
            assertEquals(before.owner(), after.owner());
            assertEquals(before.group(), after.group());
            assertEquals(before.permissions(), after.permissions());
        }
    }

    /** 验证不同用户 JSON 也不能同时维护同一个 authorized_keys，关闭后才可重新取得文件锁。 */
    @Test
    void locksSharedAuthorizedKeysAcrossDifferentUserStores() throws Exception {
        Path firstFile = directory.resolve("first.json");
        var ssh = SshRegistrationConfig.defaults(firstFile);
        Path secondFile = directory.resolve("second.json");
        // 第一个实例仅用于持有公钥文件锁，生命周期本身就是此测试的前置条件。
        try (@SuppressWarnings("unused") var first = UserStore.open(firstFile, ssh)) {
            assertOpenRejected(secondFile, ssh);
        }
        try (var second = UserStore.open(secondFile, ssh)) {
            assertNotNull(second.register("Dev", false));
        }
    }

    /** 验证存储必须拒绝打开；若意外成功，先确保资源被关闭，再让用例以明确的断言失败。 */
    private static void assertOpenRejected(Path file, SshRegistrationConfig ssh) {
        assertThrows(IOException.class, () -> {
            // 此资源预期无法创建；若实现错误地返回实例，也必须通过 try-with-resources 释放锁。
            try (@SuppressWarnings("unused") var unexpected = UserStore.open(file, ssh)) {
                fail("用户存储不应打开成功");
            }
        });
    }

    private static final class SimulatedCrash extends Error {

    }

}
