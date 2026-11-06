package top.kmar.worldrelay.server.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 探测服务端 sshd 的过期授权能力；不能确认时使用强制命令检查有效期。 */
final class OpenSshCapabilities {

    private static final System.Logger LOGGER = System.getLogger(OpenSshCapabilities.class.getName());
    private static final Pattern VERSION = Pattern.compile("(?m)^OpenSSH_(?:for_Windows_)?(\\d{1,3})\\.(\\d{1,3})(?:p\\d+)?(?=[ ,\\r\\n]|$)");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");

    enum ExpiryTime {
        UNSUPPORTED, LOCAL, UTC;

        String format(Instant expires) {
            if (this == UNSUPPORTED) throw new IllegalStateException("Native SSH key expiration is unavailable");
            return TIMESTAMP.withZone(this == UTC ? ZoneOffset.UTC : ZoneId.systemDefault()).format(expires)
                + (this == UTC ? "Z" : "");
        }
    }

    private OpenSshCapabilities() {
    }

    static ExpiryTime detect() {
        // 普通服务进程的 PATH 不一定包含 sbin；优先使用系统 sshd，而非 ssh 客户端版本。
        for (String command : List.of("/usr/sbin/sshd", "/usr/local/sbin/sshd", "sshd")) {
            String output = versionOutput(command);
            if (output != null && VERSION.matcher(output).find()) {
                ExpiryTime result = fromVersion(output);
                LOGGER.log(System.Logger.Level.INFO, result == ExpiryTime.UNSUPPORTED
                    ? "sshd lacks expiry-time; using one-hour expiration checks in the forced command"
                    : "sshd supports expiry-time; using native one-hour SSH key expiration ({0})", result);
                return result;
            }
            if (Thread.currentThread().isInterrupted()) break;
        }
        LOGGER.log(System.Logger.Level.WARNING, "Unable to detect sshd expiry-time support; using one-hour expiration checks in the forced command");
        return ExpiryTime.UNSUPPORTED;
    }

    static ExpiryTime fromVersion(String output) {
        var version = VERSION.matcher(output);
        if (!version.find()) return ExpiryTime.UNSUPPORTED;
        int major = Integer.parseInt(version.group(1)), minor = Integer.parseInt(version.group(2));
        // OpenSSH 7.7 引入 expiry-time，9.1 才支持 UTC 的 Z 后缀。
        if (major > 9 || major == 9 && minor >= 1) return ExpiryTime.UTC;
        if (major > 7 || major == 7 && minor >= 7) return ExpiryTime.LOCAL;
        return ExpiryTime.UNSUPPORTED;
    }

    private static String versionOutput(String command) {
        Process process = null;
        FutureTask<byte[]> output = null;
        try {
            process = new ProcessBuilder(command, "-V").redirectErrorStream(true).start();
            process.getOutputStream().close();
            var input = process.getInputStream();
            output = new FutureTask<>(() -> {
                try (input; var bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        // 旧 sshd 不识别 -V，会输出版本和帮助；仍需排空超出保留上限的内容。
                        bytes.write(buffer, 0, Math.min(count, 16384 - bytes.size()));
                    }
                    return bytes.toByteArray();
                }
            });
            Thread.ofVirtual().name("sshd-version").start(output);
            if (!process.waitFor(3, TimeUnit.SECONDS)) return null;
            // 旧版 -V 非零退出仍会打印真实版本，不能仅以退出码判断探测失败。
            return new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | SecurityException | ExecutionException | TimeoutException unavailable) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output != null) output.cancel(true);
        }
    }

}