package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferKeysTest {

    @TempDir
    Path root;

    /** 验证临时密钥的目录限制、过期和重启清理，不泄露私钥或改动既有凭据。 */
    @Test
    void temporaryKeysAreScopedExpiringAndRemovedOnRestartWithoutChangingRegisteredCredentials() throws Exception {
        var ssh = new SshRegistrationConfig(root.resolve("authorized_keys"), root.resolve("transfers"),
            root.resolve("unused-rrsync").toString(), "work", root.resolve("host.pub"));
        String key, registeredKeys, temporaryLine, transfer = UUID.randomUUID().toString();
        Path payload = Files.createDirectories(root.resolve("transfers/.sessions").resolve(transfer).resolve("payload"));
        try (var users = TransferTestUsers.open(root.resolve("users.json"), ssh)) {
            var user = users.register("Alice", false);
            assertNotNull(user);
            key = user.token();
            // 保留独立的已有管理员 SSH 公钥，临时授权清理不能修改它。
            registeredKeys = "ssh-ed25519 AAAA Admin\n";
            Files.writeString(ssh.authorizedKeysFile(), registeredKeys);
            String privateKey = users.issueTransferKey("Alice", transfer, payload, true);
            String keys = Files.readString(ssh.authorizedKeysFile());
            assertTrue(keys.startsWith(registeredKeys));
            assertTrue(keys.contains(" -ro "));
            assertTrue(keys.contains("/bin/date +%s"));
            assertFalse(keys.contains("expiry-time="), "兼容开发机 OpenSSH 7.6");
            assertFalse(keys.contains(privateKey));
            users.expireTransferKeys(Instant.now().plusSeconds(6 * 60 * 60 + 1));
            assertEquals(registeredKeys, Files.readString(ssh.authorizedKeysFile()));
            users.issueTransferKey("Alice", transfer, payload, false);
            temporaryLine = Files.readString(ssh.authorizedKeysFile()).substring(registeredKeys.length());
            assertThrows(java.io.IOException.class, () -> users.issueTransferKey("Alice", UUID.randomUUID().toString(), root, false));
        }
        assertEquals(registeredKeys, Files.readString(ssh.authorizedKeysFile()));
        // 模拟上次进程崩溃，临时公钥残留；下次 open 必须清除它，同时保留原账号凭据。
        Files.writeString(ssh.authorizedKeysFile(), temporaryLine, StandardOpenOption.APPEND);
        try (var users = UserStore.open(root.resolve("users.json"), ssh)) {
            assertTrue(users.verify("Alice", key));
            assertEquals(registeredKeys, Files.readString(ssh.authorizedKeysFile()));
        }
    }

}
