package top.kmar.worldrelay.sync;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 服务端生成的 rsync 参数模板；客户端只替换本机 SSH 程序和凭据配置路径。 */
public record RsyncCommand(String host, int port, String user, List<String> arguments) {

    public static final String SSH_PLACEHOLDER = "{ssh}";

    public RsyncCommand {
        host = normalizeHost(host);
        if (port < 1 || port > 65535 || !user.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid rsync endpoint");
        }
        arguments = List.copyOf(arguments);
        // 固定支持的传输模板，不能让响应携带额外程序、任意本地文件路径或 SSH 参数。
        if (!arguments.equals(template(host, user, true)) && !arguments.equals(template(host, user, false))) {
            throw new IllegalArgumentException("Unsupported rsync command");
        }
    }

    public static RsyncCommand create(String host, int port, String user, boolean upload) {
        String normalized = normalizeHost(host);
        return new RsyncCommand(normalized, port, user, template(normalized, user, upload));
    }

    @SuppressWarnings("unused") // 客户端校验传输方向；共享源码也在 server 模块中独立编译。
    public boolean upload() {
        return !arguments.getLast().equals("./");
    }

    public static String normalizeHost(String input) {
        String host = input.strip();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.contains("[") || host.contains("]") || host.contains("%")) {
            throw new IllegalArgumentException("Invalid rsync host");
        }
        try {
            if (!host.contains(":")) host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (host.length() > 253 || new URI("ssh", null, host, -1, null, null, null).getHost() == null) {
                throw new IllegalArgumentException("Invalid rsync host");
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid rsync host");
        }
    }

    private static List<String> template(String host, String user, boolean upload) {
        String remote = user + "@" + (host.contains(":") ? "[" + host + "]" : host) + ":./";
        List<String> args = new ArrayList<>(List.of("-rt", "--checksum", "--no-whole-file", "--delete-delay", "--partial",
            "--timeout=60", "--stats", "--exclude=/world/session.lock", "-e", SSH_PLACEHOLDER, "--"));
        args.add(upload ? "./" : remote);
        args.add(upload ? remote : "./");
        return List.copyOf(args);
    }

}