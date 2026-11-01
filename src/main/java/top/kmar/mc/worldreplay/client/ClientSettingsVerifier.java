package top.kmar.mc.worldreplay.client;

import top.kmar.mc.worldreplay.sync.RsyncTools;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** 按配置的 HTTP / HTTPS 协议验证连接和凭据，仅在 ping 成功后保存；应在 IO 线程调用。 */
public final class ClientSettingsVerifier {

    private ClientSettingsVerifier() {
    }

    public static void verifyAndSave(ClientConnectionSettings settings, String playerName, Path file) throws IOException {
        // 不跟随重定向，避免向其他地址转发凭据；使用 JDK 默认的证书和主机名校验。
        HttpClient client;
        try {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (RuntimeException e) {
            throw new VerificationException("ping_initialization_failed", e);
        }
        try (client) {
            verifyAndSave(settings, playerName, file, client, Duration.ofSeconds(10));
        }
    }

    /** 可注入受信任的测试客户端和超时；生产入口始终使用默认 HTTPS 校验。 */
    static void verifyAndSave(
        ClientConnectionSettings settings, String playerName, Path file,
        HttpClient client, Duration timeout
    ) throws IOException {
        RsyncTools.validate(settings.rsyncMode(), settings.rsyncPath(), settings.sshPath());
        String credentials = Base64.getEncoder().encodeToString(
            (playerName + ":" + settings.token()).getBytes(StandardCharsets.UTF_8));
        int status;
        try {
            HttpRequest request = HttpRequest.newBuilder(settings.serverUri().resolve("/api/v1/ping"))
                .timeout(timeout).header("Authorization", "Basic " + credentials)
                .header("Cache-Control", "no-cache").GET().build();
            // ping 不需要读取响应体；收到响应头后关闭流，避免等待异常响应的大量内容。
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();
            status = response.statusCode();
        } catch (HttpTimeoutException e) {
            throw new VerificationException("ping_timeout", e);
        } catch (SSLException e) {
            throw new VerificationException("ping_tls_failed", e);
        } catch (IllegalArgumentException e) {
            throw new VerificationException("ping_address_invalid", e);
        } catch (IOException | RuntimeException e) {
            throw new VerificationException("ping_connection_failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerificationException("ping_connection_failed", e);
        }
        String failure = switch (status) {
            case 204 -> null;
            case 401 -> "ping_unauthorized";
            case 404, 405 -> "ping_unsupported";
            default -> status >= 300 && status < 400 ? "ping_redirect" : "ping_response_invalid";
        };
        if (failure != null) {
            throw new VerificationException(failure);
        }
        try {
            settings.save(file);
        } catch (IOException e) {
            throw new SettingsWriteException(e);
        }
    }

    /** 完整解包异步异常；只有文件保存阶段明确报告的失败才显示写入错误。 */
    static String errorKey(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof VerificationException verification) {
            return verification.errorKey();
        }
        if (error instanceof RsyncTools.ValidationException validation) {
            return validation.errorKey();
        }
        return error instanceof SettingsWriteException ? "error.save_failed" : "error.operation_failed";
    }

    /** 日志仅记录异常类型和代码位置，不包含可能携带凭据的异常消息。 */
    static String diagnosticSummary(Throwable error) {
        StringBuilder summary = new StringBuilder();
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (depth > 0) {
                summary.append(" <- ");
            }
            summary.append(error.getClass().getName());
            StackTraceElement[] frames = error.getStackTrace();
            if (frames.length > 0) {
                summary.append(" at ").append(frames[0]);
            }
        }
        return summary.toString();
    }

    private static final class SettingsWriteException extends IOException {

        private SettingsWriteException(IOException cause) {
            super("Unable to write WorldRelay settings", cause);
        }

    }

    /** 只暴露预定义错误键，不将请求、Token 或服务端响应体放入异常信息。 */
    public static final class VerificationException extends IOException {

        private final String errorKey;

        private VerificationException(String reason) {
            this(reason, null);
        }

        private VerificationException(String reason, Throwable cause) {
            super(reason, cause);
            this.errorKey = "error." + reason;
        }

        public String errorKey() {
            return this.errorKey;
        }

    }

}