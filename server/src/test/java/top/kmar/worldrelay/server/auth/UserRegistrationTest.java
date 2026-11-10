package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class UserRegistrationTest {

    @TempDir
    Path directory;

    /** 验证 Token 重置发布前后出错时保留原账号，排除故障后可以重试。 */
    @Test
    void failedTokenResetKeepsOriginalAccountAndAllowsRetry() throws Exception {
        for (boolean afterMove : new boolean[]{false, true}) {
            Path file = directory.resolve(afterMove ? "after/users.json" : "before/users.json");
            var ssh = TransferTestUsers.config(file);
            AtomicBoolean fail = new AtomicBoolean();
            try (var users = UserStore.open(file, ssh, (temporary, target) -> {
                if (fail.get() && !afterMove) throw new IOException("before publication");
                RegistrationFiles.atomicMove(temporary, target);
                if (fail.get()) throw new IOException("after publication");
            })) {
                var original = users.register("Dev", false);
                assertNotNull(original);
                byte[] before = Files.readAllBytes(file);
                fail.set(true);
                assertThrows(IOException.class, () -> users.register("Dev", true));
                assertArrayEquals(before, Files.readAllBytes(file));
                assertTrue(users.verify("Dev", original.token()));
                assertFalse(Files.exists(file.resolveSibling("users.json.registration-pending")));
                fail.set(false);
                var reset = users.register("Dev", true);
                assertNotNull(reset);
                assertFalse(users.verify("Dev", original.token()));
                assertTrue(users.verify("Dev", reset.token()));
            }
        }
    }

    /** 验证 Token 重置中断后暂停账号服务，重启恢复原账号后才允许继续注册。 */
    @Test
    void interruptedTokenResetRecoversAccountBeforeServingRequests() throws Exception {
        for (boolean afterMove : new boolean[]{false, true}) {
            Path file = directory.resolve(afterMove ? "after/users.json" : "before/users.json");
            var ssh = TransferTestUsers.config(file);
            AtomicBoolean crash = new AtomicBoolean();
            UserStore.Registration original;
            byte[] before;
            try (var users = UserStore.open(file, ssh, (temporary, target) -> {
                if (crash.get() && !afterMove) throw new SimulatedCrash();
                RegistrationFiles.atomicMove(temporary, target);
                if (crash.get()) throw new SimulatedCrash();
            })) {
                original = users.register("Dev", false);
                assertNotNull(original);
                before = Files.readAllBytes(file);
                crash.set(true);
                assertThrows(SimulatedCrash.class, () -> users.register("Dev", true));
                assertFalse(users.verify("Dev", original.token()));
                assertThrows(IOException.class, () -> users.register("another", false));
            }
            try (var recovered = UserStore.open(file, ssh)) {
                assertArrayEquals(before, Files.readAllBytes(file));
                assertTrue(recovered.verify("Dev", original.token()));
                assertNotNull(recovered.register("another", false));
            }
        }
    }

    /** 验证恢复遇到事务之外的用户数据修改时拒绝启动并保留现场。 */
    @Test
    void recoveryRefusesUnrecognizedExternalUserChanges() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = TransferTestUsers.config(file);
        try (var users = UserStore.open(file, ssh, (temporary, target) -> {
            RegistrationFiles.atomicMove(temporary, target);
            throw new SimulatedCrash();
        })) {
            assertThrows(SimulatedCrash.class, () -> users.register("Dev", false));
        }
        String external = "{\"Admin\":\"" + "0".repeat(64) + "\"}";
        Files.writeString(file, external);
        assertOpenRejected(file, ssh);
        assertEquals(external, Files.readString(file));
        assertTrue(Files.exists(directory.resolve("users.json.registration-pending")));
    }

    /** 验证恢复备份损坏时拒绝启动，不覆盖现有用户数据。 */
    @Test
    void recoveryRejectsCorruptedBackupWithoutChangingFiles() throws Exception {
        Path file = directory.resolve("users.json");
        var ssh = TransferTestUsers.config(file);
        try (var users = UserStore.open(file, ssh, (temporary, target) -> {
            RegistrationFiles.atomicMove(temporary, target);
            throw new SimulatedCrash();
        })) {
            assertThrows(SimulatedCrash.class, () -> users.register("Dev", false));
        }
        byte[] before = Files.readAllBytes(file);
        try (var backups = Files.list(directory.resolve("users.json.registration-backups"))) {
            Files.writeString(backups.findFirst().orElseThrow(), "{}");
        }
        assertOpenRejected(file, ssh);
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    /** 验证不同用户存储不能同时维护同一公钥文件，关闭后可重新取得锁。 */
    @Test
    void locksSharedAuthorizedKeysAcrossDifferentUserStores() throws Exception {
        Path firstFile = directory.resolve("first.json");
        var ssh = TransferTestUsers.config(firstFile);
        Path secondFile = directory.resolve("second.json");
        try (@SuppressWarnings("unused") var first = UserStore.open(firstFile, ssh)) {
            assertOpenRejected(secondFile, ssh);
        }
        try (var second = UserStore.open(secondFile, ssh)) {
            assertNotNull(second.register("Dev", false));
        }
    }

    private static void assertOpenRejected(Path file, SshRegistrationConfig ssh) {
        assertThrows(IOException.class, () -> {
            try (@SuppressWarnings("unused") var unexpected = UserStore.open(file, ssh)) {
                fail("用户存储不应打开成功");
            }
        });
    }

    private static final class SimulatedCrash extends Error {

    }

}
