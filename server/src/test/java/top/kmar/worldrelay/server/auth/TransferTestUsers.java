package top.kmar.worldrelay.server.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;

/** 文件与 HTTP 测试不执行 SSH，显式注入已经通过探测的能力；真实 rrsync 另由端到端测试验证。 */
public final class TransferTestUsers {

    private TransferTestUsers() {
    }

    /** 测试公钥和传输文件始终位于用例的临时目录，不访问运行测试者的 SSH 文件。 */
    public static SshRegistrationConfig config(Path usersFile) {
        Path parent = usersFile.toAbsolutePath().getParent();
        return new SshRegistrationConfig(parent.resolve(".ssh/authorized_keys"), parent.resolve("transfers"), "/usr/bin/rrsync");
    }

    public static UserStore open(Path path, SshRegistrationConfig ssh) throws IOException {
        return UserStore.open(path, ssh, RegistrationFiles::atomicMove, command -> true, OpenSshCapabilities.ExpiryTime.UNSUPPORTED);
    }

    /** 测试 SSH 主机的独立公钥，与账号注册返回值无关。 */
    public static String hostPublicKey() throws IOException {
        return SshKeyPair.generate("test-host", new SecureRandom()).publicKey();
    }

}
