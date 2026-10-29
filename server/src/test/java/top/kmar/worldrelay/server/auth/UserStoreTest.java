package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource")
class UserStoreTest {

    @TempDir
    Path directory;

    /** 验证仅持久化密钥摘要、重启后凭据有效、用户名区分大小写，且重复注册不替换原密钥。 */
    @Test
    void persistsOnlyDigestsAndKeepsOriginalKeyOnDuplicateRegistration() throws Exception {
        Path file = directory.resolve("users.json");
        String key;
        try (UserStore store = UserStore.open(file)) {
            key = store.register("小明🌍");
            //noinspection DataFlowIssue
            assertTrue(key.matches("[A-Za-z0-9_-]{43}"));
            assertTrue(store.verify("小明🌍", key));
            assertNull(store.register("小明🌍"));
            assertTrue(store.verify("小明🌍", key));
            assertFalse(store.verify("其他人", key));
            assertFalse(store.verify("小明🌍", "wrong"));
            assertNotEquals(key, store.register("Alice"));
            assertNotNull(store.register("alice"));
        }
        String persisted = Files.readString(file);
        assertFalse(persisted.contains(key));
        assertTrue(persisted.contains("小明🌍"));
        try (UserStore reopened = UserStore.open(file)) {
            assertTrue(reopened.verify("小明🌍", key));
        }
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    /** 验证文件锁阻止多个存储实例同时打开同一文件，关闭后释放锁且禁止继续注册。 */
    @Test
    void fileLockPreventsConcurrentStoresAndIsReleasedOnClose() throws Exception {
        Path file = directory.resolve("users.json");
        UserStore first = UserStore.open(file);
        try (first) {
            assertThrows(IOException.class, () -> UserStore.open(file));
            assertThrows(IOException.class, () -> UserStore.open(directory.resolve("./users.json")));
        }
        first.close();
        assertThrows(IllegalStateException.class, () -> first.register("closed"));
        try (UserStore reopened = UserStore.open(file)) {
            assertNotNull(reopened.register("after-close"));
        }
    }

    /** 验证用户文件发布失败时不使新用户生效，清理临时文件，并允许排除故障后重试注册。 */
    @Test
    void failedPublicationDoesNotAuthenticateUserAndCanBeRetried() throws Exception {
        Path file = directory.resolve("users.json");
        try (UserStore store = UserStore.open(file)) {
            Files.createDirectory(file);
            Files.writeString(file.resolve("blocker"), "prevent replacement");
            assertThrows(IOException.class, () -> store.register("retry"));
            assertFalse(store.verify("retry", "anything"));
            assertTrue(Files.isDirectory(file));
            Files.delete(file.resolve("blocker"));
            Files.delete(file);
            String key = store.register("retry");
            assertTrue(store.verify("retry", key));
        }
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    /** 验证损坏或不可读取的用户文件会阻止打开，不改写损坏内容，并在失败后释放文件锁。 */
    @Test
    void corruptOrUnreadableStoreFailsClosedAndReleasesLock() throws Exception {
        Path file = directory.resolve("users.json");
        for (String json : new String[]{"", "[]", "{", "{\"alice\":\"bad-hash\"}",
            "{\"alice\":\"" + "0".repeat(64) + "\",\"alice\":\"" + "0".repeat(64) + "\"}",
            "{\"bad:name\":\"" + "0".repeat(64) + "\"}"}) {
            Files.writeString(file, json);
            assertThrows(IOException.class, () -> UserStore.open(file));
            assertEquals(json, Files.readString(file));
        }
        Files.writeString(file, "{}");
        try (UserStore store = UserStore.open(file)) {
            assertNotNull(store.register("ok"));
        }
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> UserStore.open(file));
    }

}