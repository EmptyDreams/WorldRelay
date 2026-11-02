package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(40)
class SshKeyPairTest {

    @TempDir
    Path directory;

    /**
     * 使用真实 OpenSSH 读取返回的私钥并导出公钥，验证文件格式与返回的公钥一致，
     * 而不仅仅检查 PEM 标记；此测试面向 Linux，缺少 POSIX 权限或 ssh-keygen 时跳过。
     */
    @Test
    void opensshCanReadGeneratedKey() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null,
            "OpenSSH interoperability is tested on Linux");
        try {
            Process probe = new ProcessBuilder("ssh-keygen", "-l", "-f", directory.resolve("absent").toString())
                .redirectErrorStream(true).start();
            probe.getOutputStream().close();
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                fail("ssh-keygen probe timed out");
            }
        } catch (IOException unavailable) {
            assumeTrue(false, "ssh-keygen is not installed");
        }
        var pair = SshKeyPair.generate("Dev", new SecureRandom());
        Path identity = writeIdentity(pair);
        String derived = run(null, "ssh-keygen", "-y", "-f", identity.toString()).strip();
        assertEquals(pair.publicKey().split(" ")[0] + " " + pair.publicKey().split(" ")[1],
            derived.split(" ")[0] + " " + derived.split(" ")[1]);
        assertFalse(pair.toString().contains(pair.privateKey()));
    }

    /** 验证支持 SSH 签名的 OpenSSH 可用生成的私钥签名并用返回的公钥验签；旧版 OpenSSH 只跳过此项。 */
    @Test
    void opensshCanSignAndVerifyGeneratedKey() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null,
            "OpenSSH interoperability is tested on Linux");
        Process probe;
        try {
            probe = new ProcessBuilder("ssh-keygen", "-Y", "sign").redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "ssh-keygen is not installed");
            return;
        }
        probe.getOutputStream().close();
        if (!probe.waitFor(10, TimeUnit.SECONDS)) {
            probe.destroyForcibly();
            fail("ssh-keygen signature probe timed out");
        }
        String usage = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assumeTrue(!usage.contains("option -- Y"), "OpenSSH does not support SSH signatures");
        var pair = SshKeyPair.generate("Dev", new SecureRandom());
        Path identity = writeIdentity(pair);
        Path message = Files.writeString(directory.resolve("message"), "WorldRelay registration proof");
        run(null, "ssh-keygen", "-Y", "sign", "-f", identity.toString(), "-n", "worldrelay", message.toString());
        Path allowed = Files.writeString(directory.resolve("allowed_signers"), "Dev " + pair.publicKey() + "\n");
        run(Files.readAllBytes(message), "ssh-keygen", "-Y", "verify", "-f", allowed.toString(), "-I", "Dev",
            "-n", "worldrelay", "-s", message + ".sig");
    }

    private Path writeIdentity(SshKeyPair pair) throws IOException {
        Path identity = Files.writeString(directory.resolve("identity"), pair.privateKey());
        Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rw-------"));
        return identity;
    }

    private static String run(byte[] input, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var output = process.getOutputStream()) {
            if (input != null) output.write(input);
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("OpenSSH verification timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

}
