package top.kmar.worldrelay.server.auth;

import top.kmar.worldrelay.sync.RsyncCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
        return new SshRegistrationConfig(authorizedKeysFile(System.getProperty("user.name", "")),
            parent.resolve("transfers"), "/usr/bin/rrsync");
    }

    /** 公钥固定放在传输账户的 ~/.ssh/authorized_keys；其他账户通过系统账号数据库查找主目录。 */
    public static Path authorizedKeysFile(String transferUser) {
        if (transferUser == null || !transferUser.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Invalid SSH transfer user");
        String home = transferUser.equals(System.getProperty("user.name"))
            ? System.getProperty("user.home") : accountHome(transferUser);
        if (home == null || home.isBlank() || !Path.of(home).isAbsolute())
            throw new IllegalArgumentException("SSH transfer user must have an absolute home directory: " + transferUser);
        return Path.of(home).resolve(".ssh/authorized_keys");
    }

    private static String accountHome(String user) {
        Process process = null;
        try {
            // 直接传参，不经 Shell；getent 同时支持本地账户和系统配置的目录服务。
            process = new ProcessBuilder("getent", "passwd", user).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            try (var input = process.getInputStream()) {
                if (!process.waitFor(3, TimeUnit.SECONDS))
                    throw new IOException("Account lookup timed out");
                byte[] output = input.readNBytes(8193);
                String[] fields = new String(output, StandardCharsets.UTF_8).strip().split(":", -1);
                if (process.exitValue() != 0 || output.length > 8192 || fields.length != 7
                    || !fields[0].equals(user) || fields[5].codePoints().anyMatch(Character::isISOControl))
                    throw new IOException("Account lookup did not return a valid home directory");
                return fields[5];
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("SSH transfer user lookup interrupted: " + user, interrupted);
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("Cannot resolve home directory for SSH transfer user: " + user, unavailable);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

}
