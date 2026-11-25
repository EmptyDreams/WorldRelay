package top.kmar.mc.worldreplay.sync;

import top.kmar.worldrelay.sync.RsyncCommand;

import java.io.BufferedInputStream;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 独立子进程，无 Shell；凭据在临时私有目录中，输出有界且不进入日志。 */
public final class RsyncTransfer {

    private static final Pattern PROGRESS_LINE = Pattern.compile(
        "\\s*[0-9][0-9,.]*(?:[kKMGTPE]i?B?|B)?\\s+(\\d{1,3})%\\s+"
            + "([0-9]+(?:\\.[0-9]+)?\\s*[kKMGTPE]?i?B/s)\\s+(?:\\d+|\\?{2}):[0-9?]{2}:[0-9?]{2}"
            + "(?:\\s+\\(xfr#\\d+, (?:ir|to)-chk=\\d+/\\d+\\))?\\s*");
    private static final Pattern STAT_LINE = Pattern.compile("(Literal|Matched) data: ([0-9,]+) bytes\\s*");

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

    /** rsync 增量重建的进度和速度，可能包含接收端复用的数据。 */
    public record Progress(int percent, String rate) {

    }

    public static Result run(
        BundledRsync.Executables tools, RsyncCommand transfer, Credentials credentials,
        Path payload, Path temporaryRoot, Consumer<Progress> onProgress
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
            var output = new FutureTask<>(() -> {
                try (var input = process.getInputStream()) {
                    return readOutput(input, onProgress);
                }
            });
            Thread drain = Thread.startVirtualThread(output);
            try {
                if (!process.waitFor(2, TimeUnit.HOURS)) throw new IOException("World transfer timed out");
                drain.join(5000);
                if (drain.isAlive() || process.exitValue() != 0) throw new IOException("World transfer failed; check SSH endpoint and retry");
                return output.get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("World transfer interrupted");
            } catch (ExecutionException failure) {
                throw new IOException("Could not read world transfer output", failure.getCause());
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
        // progress2 仅影响本地输出，服务端模板和远端 rrsync 参数保持原样。
        args.add("--info=progress2");
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

    static Result readOutput(InputStream input, Consumer<Progress> onProgress) throws IOException {
        var progress = PROGRESS_LINE.matcher("");
        var stat = STAT_LINE.matcher("");
        long literalBytes = 0, matchedBytes = 0;
        var buffer = new BufferedInputStream(input);
        byte[] line = new byte[512];
        int length = 0;
        // rsync 用 CR 覆写进度；逐条读取也让结尾的统计不受此前输出总量影响。
        while (true) {
            int value = buffer.read();
            if (value != '\r' && value != '\n' && value != -1) {
                if (length < line.length) line[length++] = (byte) value;
                continue;
            }
            if (length > 0 && length < line.length) {
                String text = new String(line, 0, length, StandardCharsets.UTF_8);
                if (progress.reset(text).matches()) {
                    int percent = Integer.parseInt(progress.group(1));
                    if (percent <= 100) onProgress.accept(new Progress(percent, progress.group(2)));
                } else if (stat.reset(text).matches()) {
                    try {
                        long count = Long.parseLong(stat.group(2).replace(",", ""));
                        if (stat.group(1).equals("Literal")) literalBytes = count;
                        else matchedBytes = count;
                    } catch (NumberFormatException ignored) { /* 统计输出不参与存档校验。 */ }
                }
            }
            if (value == -1) return new Result(literalBytes, matchedBytes);
            length = 0;
        }
    }

}
