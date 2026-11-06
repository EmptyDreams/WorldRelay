package top.kmar.worldrelay.server.auth;

import top.kmar.worldrelay.sync.RsyncCommand;

import java.nio.file.Path;
import java.util.Objects;

/** 临时传输使用的公钥文件、传输根目录及 rrsync 可执行文件；构造时不访问文件系统。 */
public record SshRegistrationConfig(Path authorizedKeysFile, Path transferDirectory, String rrsyncCommand,
                                    String transferUser, Path hostPublicKeyFile, String publicHost, int publicPort) {

    public SshRegistrationConfig(
        Path authorizedKeysFile, Path transferDirectory, String rrsyncCommand,
        String transferUser, Path hostPublicKeyFile
    ) {
        this(authorizedKeysFile, transferDirectory, rrsyncCommand, transferUser, hostPublicKeyFile, "127.0.0.1", 22);
    }

    public SshRegistrationConfig(Path authorizedKeysFile, Path transferDirectory, String rrsyncCommand) {
        this(authorizedKeysFile, transferDirectory, rrsyncCommand, System.getProperty("user.name", ""),
            Path.of("/etc/ssh/ssh_host_ed25519_key.pub"));
    }

    public SshRegistrationConfig {
        Objects.requireNonNull(authorizedKeysFile);
        Objects.requireNonNull(transferDirectory);
        Objects.requireNonNull(rrsyncCommand);
        Objects.requireNonNull(transferUser);
        Objects.requireNonNull(hostPublicKeyFile);
        if (authorizedKeysFile.toString().isBlank() || authorizedKeysFile.getFileName() == null
            || transferDirectory.toString().isBlank() || rrsyncCommand.isBlank()
            || rrsyncCommand.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid SSH registration configuration");
        }
        rrsyncCommand = rrsyncCommand.strip();
        if (!transferUser.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid SSH transfer user");
        publicHost = RsyncCommand.normalizeHost(publicHost);
        if (publicPort < 1 || publicPort > 65535) throw new IllegalArgumentException("Invalid public SSH port");
    }

    public static SshRegistrationConfig defaults(Path usersFile) {
        Path parent = usersFile.getParent() == null ? Path.of(".") : usersFile.getParent();
        return new SshRegistrationConfig(parent.resolve("ssh/authorized_keys"), parent.resolve("transfers"), "/usr/bin/rrsync");
    }

}