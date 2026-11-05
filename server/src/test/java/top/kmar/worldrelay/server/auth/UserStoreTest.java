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

    /**
     * 验证普通注册返回的 API 密钥可以认证，用户 JSON 不保存原密钥，重启后认证仍然有效。
     * 用户名区分大小写；未指定覆盖时，同名注册返回 null，并且原密钥继续有效。
     */
    @Test
    void persistsOnlyDigestsAndKeepsOriginalKeyOnDuplicateRegistration() throws Exception {
        Path file = directory.resolve("users.json");
        String key;
        try (UserStore store = UserStore.open(file)) {
            var registration = store.register("小明🌍", false);
            assertNotNull(registration, "测试前置条件：首次注册应成功");
            key = registration.token();
            assertFalse(registration.toString().contains(key));
            assertTrue(key.matches("[A-Za-z0-9_-]{43}"));
            assertTrue(store.verify("小明🌍", key));
            assertNull(store.register("小明🌍", false));
            assertTrue(store.verify("小明🌍", key));
            assertFalse(store.verify("其他人", key));
            assertFalse(store.verify("小明🌍", "wrong"));
            var alice = store.register("Alice", false);
            assertNotNull(alice, "测试前置条件：不同用户名应注册成功");
            assertNotEquals(key, alice.token());
            assertNotNull(store.register("alice", false));
        }
        String persisted = Files.readString(file);
        assertFalse(persisted.contains(key));
        assertTrue(persisted.contains("小明🌍"));
        try (UserStore reopened = UserStore.open(file)) {
            assertTrue(reopened.verify("小明🌍", key));
        }
    }

    /**
     * 验证用户文件及其等价路径不能被两个存储实例同时打开，防止并发修改同一份用户数据。
     * 关闭后原实例禁止注册，重新打开的实例可以注册，确认文件锁已释放且关闭操作可重复调用。
     */
    @Test
    void fileLockPreventsConcurrentStoresAndIsReleasedOnClose() throws Exception {
        Path file = directory.resolve("users.json");
        UserStore first = UserStore.open(file);
        try (first) {
            assertThrows(IOException.class, () -> UserStore.open(file));
            assertThrows(IOException.class, () -> UserStore.open(directory.resolve("./users.json")));
        }
        first.close();
        assertThrows(IllegalStateException.class, () -> first.register("closed", false));
        try (UserStore reopened = UserStore.open(file)) {
            assertNotNull(reopened.register("after-close", false));
        }
    }

    /**
     * 用同名目录阻止用户 JSON 写入，验证注册失败不会使新用户生效，也不会覆盖障碍目录。
     * 移除障碍后可重新注册并使用返回的 API 密钥认证。
     */
    @Test
    void failedPublicationDoesNotAuthenticateUserAndCanBeRetried() throws Exception {
        Path file = directory.resolve("users.json");
        try (UserStore store = UserStore.open(file)) {
            Files.createDirectory(file);
            Files.writeString(file.resolve("blocker"), "prevent replacement");
            assertThrows(IOException.class, () -> store.register("retry", false));
            assertFalse(store.verify("retry", "anything"));
            assertTrue(Files.isDirectory(file));
            Files.delete(file.resolve("blocker"));
            Files.delete(file);
            var retried = store.register("retry", false);
            assertNotNull(retried, "测试前置条件：排除写入故障后应注册成功");
            String key = retried.token();
            assertTrue(store.verify("retry", key));
        }
    }

    /**
     * 验证空文件、损坏 JSON、重复用户名、非法摘要及非法用户名均被拒绝加载，原文件内容保持不变。
     * 每次失败都释放文件锁，修复文件后可以正常注册；用户文件路径被目录占用时同样拒绝打开。
     */
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
            assertNotNull(store.register("ok", false));
        }
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> UserStore.open(file));
    }

}
