package top.kmar.mc.worldreplay.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import top.kmar.mc.worldreplay.sync.RsyncMode;

import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** 保存客户端连接和工具配置；玩家名始终读取当前账号，不写入配置。 */
public record ClientConnectionSettings(String serverAddress, int serverPort, String token,
                                       RsyncMode rsyncMode, String rsyncPath, String sshPath) {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX_TOKEN_LENGTH = 4096;
    public static final int MAX_RSYNC_PATH_LENGTH = 4096;

    public static ClientConnectionSettings defaults() {
        return new ClientConnectionSettings("", 443, "", RsyncMode.defaultMode(), "", "");
    }

    /** 接受 HTTP / HTTPS 地址；省略协议时默认 HTTPS，端口单独填写。 */
    public static ClientConnectionSettings fromInput(
        String domainInput, String portInput, String tokenInput,
        RsyncMode mode, String rsyncPathInput, String sshPathInput
    ) {
        String address = domainInput.strip();
        if (address.isEmpty()) {
            throw new ValidationException("domain_required");
        }

        String domain;
        String protocol;
        try {
            URI uri = new URI(address.contains("://") ? address : "https://" + address);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                throw new ValidationException("protocol_invalid");
            }
            protocol = scheme.toLowerCase(Locale.ROOT);
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
        if (mode == null || !RsyncMode.availableModes().contains(mode)) {
            throw new ValidationException("rsync_mode_unavailable");
        }
        String rsyncPath = rsyncPathInput.strip();
        String sshPath = sshPathInput.strip();
        if (mode == RsyncMode.CUSTOM) {
            rsyncPath = validateToolPath(rsyncPath, "rsync");
            sshPath = validateToolPath(sshPath, "ssh");
        }
        return new ClientConnectionSettings(protocol + "://" + domain, port, token, mode, rsyncPath, sshPath);
    }

    private static String validateToolPath(String value, String tool) {
        if (value.isEmpty()) {
            throw new ValidationException(tool + "_path_required");
        }
        if (value.length() > MAX_RSYNC_PATH_LENGTH || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ValidationException(tool + "_path_invalid");
        }
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                throw new ValidationException(tool + "_path_invalid");
            }
            return path.normalize().toString();
        } catch (InvalidPathException e) {
            throw new ValidationException(tool + "_path_invalid");
        }
    }

    /** 网络层使用用户选择的协议，不自动降级或升级。 */
    public URI serverUri() {
        var checked = fromInput(serverAddress, Integer.toString(serverPort), token, rsyncMode, rsyncPath, sshPath);
        return URI.create(checked.serverAddress + ":" + checked.serverPort);
    }

    public boolean usesHttp() {
        return "http".equals(serverUri().getScheme());
    }

    public static ClientConnectionSettings load(Path file) throws IOException {
        if (Files.notExists(file)) {
            return defaults();
        }
        try (var reader = Files.newBufferedReader(file)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            return fromInput(json.get("serverAddress").getAsString(), json.get("serverPort").getAsString(),
                json.get("token").getAsString(), RsyncMode.valueOf(json.get("rsyncMode").getAsString()),
                json.get("rsyncPath").getAsString(), json.get("sshPath").getAsString());
        } catch (RuntimeException e) {
            // 解析异常可能包含原始配置内容，不将 token 放入异常链或日志。
            throw new IOException("Invalid WorldRelay client settings");
        }
    }

    /** 在同目录写完临时文件后原子替换，失败时保留已有配置；调用方应在 IO 线程执行。 */
    public void save(Path file) throws IOException {
        var checked = fromInput(serverAddress, Integer.toString(serverPort), token, rsyncMode, rsyncPath, sshPath);
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
        return "ClientConnectionSettings[serverAddress=" + serverAddress + ", serverPort=" + serverPort
            + ", token=<redacted>, rsyncMode=" + rsyncMode + ", rsyncPath=" + rsyncPath + ", sshPath=" + sshPath + "]";
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
