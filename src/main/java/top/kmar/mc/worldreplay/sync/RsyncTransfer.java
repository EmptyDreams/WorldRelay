package top.kmar.mc.worldreplay.sync;

import top.kmar.worldrelay.sync.RsyncCommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 独立子进程，无 Shell；凭据在临时私有目录中，输出有界且不进入日志。 */
public final class RsyncTransfer {

    private RsyncTransfer() {
    }

    public record Credentials(String privateKey, String hostPublicKey) {

        public Credentials {
            if (!hostPublicKey.matches("ssh-ed25519 [A-Za-z0-9+/]+={0,2}")
                || privateKey.length() > 8192 || !privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n")
                || !privateKey.endsWith("-----END OPENSSH PRIVATE KEY-----\n")) throw new IllegalArgumentException("Invalid SSH credentials");
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public String toString() {
            return "Credentials[privateKey=<redacted>]";
        }

    }

    public record Result(long literalBytes, long matchedBytes) {

    }

    public static Result run(
        BundledRsync.Executables tools, RsyncCommand transfer, Credentials credentials,
        Path payload, Path temporaryRoot
    ) throws IOException {
        Files.createDirectories(temporaryRoot);
        Path secret = Files.createTempDirectory(temporaryRoot, "ssh-");
        boolean posix = Files.getFileStore(secret).supportsFileAttributeView("posix");
        if (posix) Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rwx------"));
        else privateAcl(secret, true);
        try {
            Path identity = secret.resolve("identity");
            Path knownHosts = secret.resolve("known_hosts");
            Path config = secret.resolve("config");
            String host = transfer.host();
            String knownHost = transfer.port() == 22 ? host : "[" + host + "]:" + transfer.port();
            writeSecret(identity, credentials.privateKey, posix);
            writeSecret(knownHosts, knownHost + " " + credentials.hostPublicKey + "\n", posix);
            writeSecret(config, "Host *\n  BatchMode yes\n  IdentitiesOnly yes\n  IdentityAgent none\n"
                + "  StrictHostKeyChecking yes\n  UpdateHostKeys no\n  GlobalKnownHostsFile " + configPath(secret.resolve("unused_known_hosts")) + "\n"
                + "  UserKnownHostsFile " + configPath(knownHosts) + "\n  IdentityFile " + configPath(identity) + "\n"
                + "  ConnectTimeout 10\n  ServerAliveInterval 15\n  ServerAliveCountMax 3\n"
                + "  PasswordAuthentication no\n  KbdInteractiveAuthentication no\n  LogLevel ERROR\n", posix);
            List<String> command = command(tools, transfer, config);
            ProcessBuilder builder = new ProcessBuilder(command).directory(payload.toFile()).redirectErrorStream(true);
            builder.environment().put("LC_ALL", "C");
            // 不让用户环境中的 rsync 配置覆盖显式选择的 SSH 程序或隐藏参数。
            builder.environment().keySet().removeIf(name -> name.startsWith("RSYNC_"));
            Process process = builder.start();
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread drain = Thread.startVirtualThread(() -> {
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (output.size() < 65536) output.write(buffer, 0, Math.min(read, 65536 - output.size()));
                    }
                } catch (IOException ignored) { /* 超时或取消会关闭进程管道。 */ }
            });
            try {
                if (!process.waitFor(2, TimeUnit.HOURS)) throw new IOException("World transfer timed out");
                drain.join(5000);
                if (drain.isAlive() || process.exitValue() != 0) throw new IOException("World transfer failed; check SSH endpoint and retry");
                String stats = output.toString(StandardCharsets.UTF_8);
                return new Result(stat(stats, "Literal data"), stat(stats, "Matched data"));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("World transfer interrupted");
            } finally {
                process.descendants().forEach(child -> {
                    child.destroy();
                    if (child.isAlive()) child.destroyForcibly();
                });
                if (process.isAlive()) {
                    process.destroy();
                    if (process.isAlive()) process.destroyForcibly();
                }
            }
        } finally {
            // 只清理本次创建的四个固定凭据文件，绝不递归删除世界或其它工作目录。
            for (String name : List.of("config", "known_hosts", "identity")) {
                Files.deleteIfExists(secret.resolve(name));
            }
            Files.deleteIfExists(secret);
        }
    }

    static List<String> command(BundledRsync.Executables tools, RsyncCommand transfer, Path config) throws IOException {
        String ssh = quote(tools.ssh().toAbsolutePath().toString().replace('\\', '/')) + " -F "
            + quote(config.toAbsolutePath().toString().replace('\\', '/')) + " -p " + transfer.port();
        List<String> args = new ArrayList<>();
        args.add(tools.rsync().toAbsolutePath().toString());
        // 受管工作目录作为 cwd，避免 Windows 的盘符被当成 HOST:PATH。
        for (String argument : transfer.arguments()) {
            args.add(argument.equals(RsyncCommand.SSH_PLACEHOLDER) ? ssh : argument);
        }
        return List.copyOf(args);
    }

    private static String quote(String value) throws IOException {
        if (value.indexOf('"') >= 0 || value.codePoints().anyMatch(Character::isISOControl)) throw new IOException("Unsupported tool path");
        return "\"" + value + "\"";
    }

    private static String configPath(Path file) throws IOException {
        // OpenSSH 对路径解释 % 变量；%% 保留用户目录中的字面百分号。
        return quote(file.toAbsolutePath().toString().replace('\\', '/').replace("%", "%%"));
    }

    private static void writeSecret(Path path, String content, boolean posix) throws IOException {
        if (posix) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        else Files.createFile(path);
        if (!posix) privateAcl(path, false);
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void privateAcl(Path path, boolean directory) throws IOException {
        var view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) throw new IOException("Private SSH credential permissions are unavailable");
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        view.setAcl(List.of(entry.build()));
    }

    private static long stat(String output, String name) {
        var matcher = java.util.regex.Pattern.compile("(?m)^" + name + ": ([0-9,]+) bytes").matcher(output);
        return matcher.find() ? Long.parseLong(matcher.group(1).replace(",", "")) : 0;
    }

}