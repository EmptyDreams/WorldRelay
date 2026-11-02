package top.kmar.worldrelay.server.auth;

import java.nio.file.Path;
import java.util.Objects;

/** 注册时维护的公钥文件、用户传输根目录及 rrsync 可执行文件；构造时不访问文件系统。 */
public record SshRegistrationConfig(Path authorizedKeysFile, Path transferDirectory, String rrsyncCommand) {

    public SshRegistrationConfig {
        Objects.requireNonNull(authorizedKeysFile);
        Objects.requireNonNull(transferDirectory);
        Objects.requireNonNull(rrsyncCommand);
        if (authorizedKeysFile.toString().isBlank() || authorizedKeysFile.getFileName() == null
            || transferDirectory.toString().isBlank() || rrsyncCommand.isBlank()
            || rrsyncCommand.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid SSH registration configuration");
        }
        rrsyncCommand = rrsyncCommand.strip();
    }

    public static SshRegistrationConfig defaults(Path usersFile) {
        Path parent = usersFile.getParent() == null ? Path.of(".") : usersFile.getParent();
        return new SshRegistrationConfig(parent.resolve("ssh/authorized_keys"), parent.resolve("transfers"), "/usr/bin/rrsync");
    }

}
