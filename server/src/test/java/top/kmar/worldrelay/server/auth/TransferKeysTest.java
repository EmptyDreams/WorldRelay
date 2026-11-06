package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TransferKeysTest {

    @TempDir
    Path root;

    /** 验证各类 sshd 使用的一小时授权期限、目录限制和重启清理，不泄露私钥或改动既有凭据。 */
    @ParameterizedTest
    @EnumSource(OpenSshCapabilities.ExpiryTime.class)
    void temporaryKeysAreScopedExpiringAndRemovedOnRestartWithoutChangingRegisteredCredentials(OpenSshCapabilities.ExpiryTime expiryTime) throws Exception {
        var ssh = new SshRegistrationConfig(root.resolve("authorized_keys"), root.resolve("transfers"),
            root.resolve("unused-rrsync").toString(), "work", root.resolve("host.pub"));
        String key, registeredKeys, temporaryLine, transfer = UUID.randomUUID().toString();
        Path payload = Files.createDirectories(root.resolve("transfers/.sessions").resolve(transfer).resolve("payload"));
        try (var users = UserStore.open(root.resolve("users.json"), ssh, RegistrationFiles::atomicMove,
            command -> true, expiryTime)) {
            var user = users.register("Alice", false);
            assertNotNull(user);
            key = user.token();
            // 保留独立的已有管理员 SSH 公钥，临时授权清理不能修改它。
            registeredKeys = "ssh-ed25519 AAAA Admin\n";
            Files.writeString(ssh.authorizedKeysFile(), registeredKeys);
            Instant issuedAt = Instant.now();
            String privateKey = users.issueTransferKey("Alice", transfer, payload, true);
            String keys = Files.readString(ssh.authorizedKeysFile());
            assertTrue(keys.startsWith(registeredKeys));
            assertTrue(keys.contains(" -ro "));
            assertFalse(keys.contains(privateKey));
            Instant expires;
            if (expiryTime == OpenSshCapabilities.ExpiryTime.UNSUPPORTED) {
                var deadline = Pattern.compile(" -lt ([0-9]+) ").matcher(keys);
                assertTrue(deadline.find());
                expires = Instant.ofEpochSecond(Long.parseLong(deadline.group(1)));
            } else {
                var deadline = Pattern.compile("expiry-time=\"([0-9]{14})(Z?)\"").matcher(keys);
                assertTrue(deadline.find());
                assertEquals(expiryTime == OpenSshCapabilities.ExpiryTime.UTC ? "Z" : "", deadline.group(2));
                var zone = expiryTime == OpenSshCapabilities.ExpiryTime.UTC ? ZoneOffset.UTC : ZoneId.systemDefault();
                expires = LocalDateTime.parse(deadline.group(1), DateTimeFormatter.ofPattern("uuuuMMddHHmmss")).atZone(zone).toInstant();
            }
            assertFalse(expires.isBefore(issuedAt.plusSeconds(3599)));
            assertFalse(expires.isAfter(Instant.now().plusSeconds(3600)));
            users.expireTransferKeys(Instant.now().plusSeconds(59 * 60));
            assertEquals(keys, Files.readString(ssh.authorizedKeysFile()));
            users.expireTransferKeys(Instant.now().plusSeconds(60 * 60 + 1));
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
