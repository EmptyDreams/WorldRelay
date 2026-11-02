package top.kmar.worldrelay.server.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 启动时探测 rrsync 的可选参数；探测失败只省略 -munge，不阻止服务启动。 */
final class RrsyncCapabilities {

    private static final System.Logger LOGGER = System.getLogger(RrsyncCapabilities.class.getName());
    private static final Pattern MUNGE_OPTION = Pattern.compile("(?<![\\w-])-munge(?![\\w-])");
    private static final int MAX_HELP_BYTES = 64 * 1024;

    private RrsyncCapabilities() {
    }

    static boolean supportsMunge(String command) {
        return supportsMunge(command, Duration.ofSeconds(3));
    }

    static boolean supportsMunge(String command, Duration timeout) {
        Process process = null;
        FutureTask<byte[]> output = null;
        boolean supported = false;
        String reason = "option not advertised by rrsync -help";
        try {
            var builder = new ProcessBuilder(command, "-help").redirectErrorStream(true);
            builder.environment().remove("SSH_ORIGINAL_COMMAND");
            process = builder.start();
            process.getOutputStream().close();
            var input = process.getInputStream();
            output = new FutureTask<>(() -> {
                try (input; var help = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        // 达到上限后仍排空管道，避免帮助文本过长导致子进程阻塞。
                        help.write(buffer, 0, Math.min(count, MAX_HELP_BYTES - help.size()));
                    }
                    return help.toByteArray();
                }
            });
            Thread.ofVirtual().name("rrsync-help").start(output);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                reason = "capability check timed out";
            } else if (process.exitValue() == 0) {
                String help = new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
                supported = MUNGE_OPTION.matcher(help).find();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reason = "capability check interrupted";
        } catch (IOException | SecurityException | ExecutionException | TimeoutException unavailable) {
            reason = "unable to read rrsync capabilities";
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output != null) output.cancel(true);
        }
        if (supported) {
            LOGGER.log(System.Logger.Level.INFO, "rrsync supports -munge; enabling it for SSH authorizations");
        } else {
            LOGGER.log(System.Logger.Level.INFO, "rrsync -munge unavailable ({0}); SSH authorizations will omit it", reason);
        }
        return supported;
    }

}
