package top.kmar.worldrelay.server.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;

/** 文件与 HTTP 测试不执行 SSH，显式注入已经通过探测的能力；真实 rrsync 另由端到端测试验证。 */
public final class TransferTestUsers {

    private TransferTestUsers() {
    }

    public static UserStore open(Path path, SshRegistrationConfig ssh) throws IOException {
        return UserStore.open(path, ssh, RegistrationFiles::atomicMove, command -> true, OpenSshCapabilities.ExpiryTime.UNSUPPORTED);
    }

    /** 测试 SSH 主机的独立公钥，与账号注册返回值无关。 */
    public static String hostPublicKey() throws IOException {
        return SshKeyPair.generate("test-host", new SecureRandom()).publicKey();
    }

}
