package top.kmar.mc.worldreplay.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** 从模组资源提取 Windows x64 工具；所有文件 IO 均须由调用方安排在后台线程。 */
public final class BundledRsync {

    private static final String RESOURCE_ROOT = "/worldrelay/tools/windows-x64/";
    private static final List<String> FILES = List.of(
        "rsync.exe", "ssh.exe", "COPYING.txt", "NOTICE-ssh.txt", "RELEASE-NOTES.txt");

    private BundledRsync() {
    }

    public static boolean isNotSupportedPlatform() {
        return !isSupportedPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        String arch = architecture.toLowerCase(Locale.ROOT);
        return osName.toLowerCase(Locale.ROOT).startsWith("windows")
            && (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64"));
    }

    /**
     * 提取到游戏目录下与 mods 同级的 worldrelay-tools；已有文件逐一校验，正确时不改写。
     * 不执行安装脚本，不修改 PATH，不安装 Windows 组件，也不启动任意程序。
     */
    public static Executables extract(Path gameDirectory) throws IOException {
        return extract(gameDirectory, System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    // 同进程调用串行化，文件锁另外保护共用游戏目录的不同客户端进程。
    static synchronized Executables extract(Path gameDirectory, String osName, String architecture) throws IOException {
        if (!isSupportedPlatform(osName, architecture)) {
            throw new UnsupportedOperationException("Bundled rsync is only available for Windows x64");
        }

        Properties checksums = new Properties();
        try (InputStream input = resource("checksums.properties")) {
            checksums.load(input);
        }
        for (String name : FILES) {
            String checksum = checksums.getProperty(name, "");
            if (!checksum.matches("[0-9a-f]{64}")) {
                throw new IOException("Missing or invalid bundled checksum for " + name);
            }
        }

        Path tools = gameDirectory.toAbsolutePath().normalize().resolve("worldrelay-tools");
        createDirectory(tools);
        try (FileChannel channel = FileChannel.open(tools.resolve(".extract.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            for (String name : FILES) {
                extractFile(tools, name, checksums.getProperty(name));
            }
            // 只有全部程序和许可文件均校验成功，才向调用方提供可用路径。
            return new Executables(tools.resolve("rsync.exe"), tools.resolve("ssh.exe"));
        }
    }

    private static void createDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tool directory must be a real directory: " + path);
        }
    }

    private static void extractFile(Path directory, String name, String expectedChecksum) throws IOException {
        Path target = directory.resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Tool file must be a regular file: " + target);
            }
            if (checksum(target).equals(expectedChecksum)) {
                return;
            }
        }

        Path temporary = Files.createTempFile(directory, name + "-", ".tmp");
        try {
            try (InputStream input = resource(name)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!checksum(temporary).equals(expectedChecksum)) {
                throw new IOException("Bundled resource checksum mismatch: " + name);
            }
            // 失败或进程中断不会把半个程序暴露为正式 exe；下次调用会重新检查并重试。
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = BundledRsync.class.getResourceAsStream(RESOURCE_ROOT + name);
        if (input == null) {
            throw new IOException("Missing bundled rsync resource: " + name);
        }
        return input;
    }

    private static String checksum(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    /** 两个绝对路径始终处于同一目录，供后续 ProcessBuilder 调用。 */
    public record Executables(Path rsync, Path ssh) {

    }

}