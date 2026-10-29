package top.kmar.mc.worldreplay.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** 仅保存客户端连接配置；玩家名始终读取当前账号，不写入配置。 */
public record ClientConnectionSettings(String serverDomain, int serverPort, String token) {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX_TOKEN_LENGTH = 4096;

    public static ClientConnectionSettings defaults() {
        return new ClientConnectionSettings("", 443, "");
    }

    /** 接受纯域名或粘贴的 HTTPS 地址；端口必须单独填写，不接受路径、凭据或查询参数。 */
    public static ClientConnectionSettings fromInput(String domainInput, String portInput, String tokenInput) {
        String address = domainInput.strip();
        if (address.isEmpty()) {
            throw new ValidationException("domain_required");
        }

        String domain;
        try {
            URI uri = new URI(address.contains("://") ? address : "https://" + address);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new ValidationException("https_required");
            }
            String authority = uri.getRawAuthority();
            if (authority == null || authority.contains(":") || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
                throw new ValidationException("domain_invalid");
            }
            domain = IDN.toASCII(authority, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (domain.isEmpty() || domain.length() > 253) {
                throw new ValidationException("domain_invalid");
            }
        } catch (URISyntaxException e) {
            throw new ValidationException("domain_invalid");
        } catch (IllegalArgumentException e) {
            if (e instanceof ValidationException validation) {
                throw validation;
            }
            throw new ValidationException("domain_invalid");
        }

        String portText = portInput.strip();
        if (!portText.matches("[0-9]{1,5}")) {
            throw new ValidationException("port_invalid");
        }
        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) {
            throw new ValidationException("port_invalid");
        }

        String token = tokenInput.strip();
        if (token.isEmpty()) {
            throw new ValidationException("token_required");
        }
        if (token.length() > MAX_TOKEN_LENGTH
            || token.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new ValidationException("token_invalid");
        }
        return new ClientConnectionSettings(domain, port, token);
    }

    /** 网络层从这里取得地址，协议固定为 HTTPS。 */
    public URI serverUri() {
        var checked = fromInput(serverDomain, Integer.toString(serverPort), token);
        return URI.create("https://" + checked.serverDomain + ":" + checked.serverPort);
    }

    public static ClientConnectionSettings load(Path file) throws IOException {
        if (Files.notExists(file)) {
            return defaults();
        }
        try (var reader = Files.newBufferedReader(file)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            return fromInput(json.get("serverDomain").getAsString(), json.get("serverPort").getAsString(),
                json.get("token").getAsString());
        } catch (RuntimeException e) {
            // 解析异常可能包含原始配置内容，不将 token 放入异常链或日志。
            throw new IOException("Invalid WorldRelay client settings");
        }
    }

    /** 在同目录写完临时文件后原子替换，失败时保留已有配置；调用方应在 IO 线程执行。 */
    public void save(Path file) throws IOException {
        var checked = fromInput(serverDomain, Integer.toString(serverPort), token);
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "worldrelay-client-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(checked, writer);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public @NotNull String toString() {
        return "ClientConnectionSettings[serverDomain=" + serverDomain + ", serverPort=" + serverPort + ", token=<redacted>]";
    }

    public static final class ValidationException extends IllegalArgumentException {

        private final String translationKey;

        private ValidationException(String reason) {
            super(reason);
            this.translationKey = "worldrelay.settings.error." + reason;
        }

        public String translationKey() {
            return translationKey;
        }

    }

}