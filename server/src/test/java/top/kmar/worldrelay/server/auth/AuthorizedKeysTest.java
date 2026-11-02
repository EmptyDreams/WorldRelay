package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizedKeysTest {

    /** 验证用户名按完整注释精确匹配，不能因前缀或注释中包含目标名字而改写其他用户的普通密钥。 */
    @Test
    void matchesEntireCommentInsteadOfSubstrings() throws Exception {
        String original = "ssh-ed25519 AAAA Developer\nssh-ed25519 AAAA Not Dev\n";
        String updated = AuthorizedKeys.update(original, "Dev", "ssh-ed25519 BBBB Dev", "/usr/bin/rrsync", Path.of("staging"), true);
        assertTrue(updated.startsWith(original));
        assertEquals(3, updated.lines().count());
    }

    /** 验证仅名称类似 rrsync、夹带额外命令、格式损坏或同名多行时拒绝猜测和替换。 */
    @Test
    void rejectsUnmanagedCommandsAndAmbiguousEntries() {
        for (String original : new String[]{
            "command=\"rrsync-helper /data\" ssh-ed25519 AAAA Dev\n",
            "command=\"rrsync /data; sh\" ssh-ed25519 AAAA Dev\n",
            "command=\"echo rrsync /data\" ssh-ed25519 AAAA Dev\n",
            "command=\"rrsync /data ssh-ed25519 AAAA Dev\n",
            "command=\"rrsync /one\" ssh-ed25519 AAAA Dev\ncommand=\"rrsync /two\" ssh-ed25519 AAAA Dev\n"
        }) {
            assertThrows(UserStore.AuthorizationConflictException.class,
                () -> AuthorizedKeys.update(original, "Dev", "ssh-ed25519 BBBB Dev", "rrsync", Path.of("staging"), true));
        }
    }

    /** 验证含空格和引号的路径能安全编码，并且生成的 rrsync 行可在下次重置时被重新识别。 */
    @Test
    void safelyQuotesPathsAndRecognizesGeneratedLines() throws Exception {
        String name = "用户 Dev";
        Path path = Path.of("staging user's directory");
        String first = AuthorizedKeys.update("", name, "ssh-ed25519 AAAA " + name, "/opt/sync tools/rrsync", path, true);
        String second = AuthorizedKeys.update(first, name, "ssh-ed25519 BBBB " + name, "/opt/sync tools/rrsync", path, true);
        assertEquals(1, second.lines().count());
        assertTrue(second.contains("ssh-ed25519 BBBB " + name));
        assertFalse(second.contains("ssh-ed25519 AAAA"));
        assertTrue(second.startsWith("restrict,command=\""));
    }

    /** 验证不支持 -munge 时省略参数，并能将已有带参数的 rrsync 行替换为无参数授权，保留其他限制。 */
    @Test
    void omitsMungeWhenUnsupportedAndCanReplacePreviouslyEnabledEntry() throws Exception {
        Path path = Path.of("staging");
        String first = AuthorizedKeys.update("", "Dev", "ssh-ed25519 AAAA Dev", "rrsync", path, true);
        assertTrue(first.contains(" -munge "));
        String second = AuthorizedKeys.update(first, "Dev", "ssh-ed25519 BBBB Dev", "rrsync", path, false);
        assertEquals("restrict,command=\"'rrsync' 'staging'\" ssh-ed25519 BBBB Dev\n", second);
    }

}
