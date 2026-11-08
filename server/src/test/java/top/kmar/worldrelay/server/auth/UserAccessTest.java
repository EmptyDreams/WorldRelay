package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class UserAccessTest {

    @TempDir
    Path root;

    private SshRegistrationConfig ssh() {
        return new SshRegistrationConfig(root.resolve("ssh/authorized_keys"), root.resolve("transfers"), "/usr/bin/rrsync", "work", root.resolve("host.pub"));
    }

    private Path payload(String name) throws IOException {
        return Files.createDirectories(ssh().transferDirectory().resolve(".sessions").resolve(name));
    }

    /** 禁用只撤销该用户的传输授权并持久化；重置 Token 不会绕过禁用，启用后新 Token 可用。 */
    @Test
    void persistsDisabledUsersAndRevokesOnlyTheirAuthorizations() throws Exception {
        Path file = root.resolve("users.json");
        String alice, bob;
        String aliceTransfer = UUID.randomUUID().toString(), bobTransfer = UUID.randomUUID().toString();
        try (var users = TransferTestUsers.open(file, ssh())) {
            alice = java.util.Objects.requireNonNull(users.register("Alice", false)).token();
            bob = java.util.Objects.requireNonNull(users.register("Bob", false)).token();
            Files.writeString(ssh().authorizedKeysFile(), "# unrelated administrator entry\n");
            users.issueTransferKey("Alice", aliceTransfer, payload("alice"), false);
            users.issueTransferKey("Bob", bobTransfer, payload("bob"), true);
            var disabled = users.setEnabled("Alice", false);
            assertNotNull(disabled);
            assertFalse(disabled.enabled());
            assertEquals(0, disabled.transferAuthorizations());
            assertFalse(users.verify("Alice", alice));
            assertTrue(users.verify("Bob", bob));
            String keys = Files.readString(ssh().authorizedKeysFile());
            assertFalse(keys.contains(aliceTransfer));
            assertTrue(keys.contains(bobTransfer));
            assertTrue(keys.contains("# unrelated administrator entry"));
            assertThrows(IOException.class, () -> users.issueTransferKey("Alice", UUID.randomUUID().toString(), payload("alice"), false));
        }
        try (var users = TransferTestUsers.open(file, ssh())) {
            assertFalse(users.isEnabled("Alice"));
            assertFalse(users.verify("Alice", alice));
            assertTrue(users.verify("Bob", bob));
            String newToken = java.util.Objects.requireNonNull(users.register("Alice", true)).token();
            assertFalse(users.verify("Alice", newToken));
            assertTrue(java.util.Objects.requireNonNull(users.setEnabled("Alice", true)).enabled());
            assertTrue(users.verify("Alice", newToken));
            assertFalse(users.verify("Alice", alice));
            assertEquals(2, users.list().size());
            assertFalse(Files.readString(file).contains(newToken));
        }
    }

    /** 禁用事务在公钥写入后失败时，用户状态和公钥必须一起回滚。 */
    @Test
    void rollsBackAccountAndKeysWhenDisablingCannotBePublished() throws Exception {
        Path file = root.resolve("users.json");
        var fail = new AtomicBoolean();
        try (var users = UserStore.open(file, ssh(), (source, target) -> {
            if (target.equals(file) && fail.getAndSet(false)) throw new IOException("injected user publication failure");
            RegistrationFiles.atomicMove(source, target);
        }, command -> true, OpenSshCapabilities.ExpiryTime.UNSUPPORTED)) {
            String token = java.util.Objects.requireNonNull(users.register("Alice", false)).token();
            users.issueTransferKey("Alice", UUID.randomUUID().toString(), payload("alice"), false);
            byte[] beforeUsers = Files.readAllBytes(file), beforeKeys = Files.readAllBytes(ssh().authorizedKeysFile());
            fail.set(true);
            assertThrows(IOException.class, () -> users.setEnabled("Alice", false));
            assertTrue(users.verify("Alice", token));
            assertTrue(users.isEnabled("Alice"));
            assertArrayEquals(beforeUsers, Files.readAllBytes(file));
            assertArrayEquals(beforeKeys, Files.readAllBytes(ssh().authorizedKeysFile()));
            assertEquals(1, users.list().getFirst().transferAuthorizations());
        }
    }

}