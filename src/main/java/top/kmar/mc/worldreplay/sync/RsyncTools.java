package top.kmar.mc.worldreplay.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** 按明确的来源选择同步程序；文件检查须在 IO 线程执行，不启动程序或解释命令行。 */
public final class RsyncTools {

    private RsyncTools() {
    }

    public static BundledRsync.Executables prepare(Path gameDirectory, RsyncMode mode, String rsyncPath, String sshPath)
        throws IOException {
        return switch (mode) {
            case BUNDLED -> BundledRsync.extract(gameDirectory);
            case AUTO -> findInstalled(isWindows(), System.getenv("PATH"));
            case CUSTOM -> customExecutables(rsyncPath, sshPath);
        };
    }

    /** 保存前校验所选来源；内置模式仅检查平台，不为保存配置而重新提取文件。 */
    public static void validate(RsyncMode mode, String rsyncPath, String sshPath) throws ValidationException {
        switch (mode) {
            case BUNDLED -> {
                if (BundledRsync.isNotSupportedPlatform()) {
                    throw new ValidationException("rsync_mode_unavailable");
                }
            }
            case AUTO -> findInstalled(isWindows(), System.getenv("PATH"));
            case CUSTOM -> customExecutables(rsyncPath, sshPath);
        }
    }

    static BundledRsync.Executables customExecutables(String rsyncPath, String sshPath) throws ValidationException {
        return new BundledRsync.Executables(customExecutable(rsyncPath, "rsync"), customExecutable(sshPath, "ssh"));
    }

    private static Path customExecutable(String value, String tool) throws ValidationException {
        if (value.isBlank()) {
            throw new ValidationException(tool + "_path_required");
        }
        Path executable;
        try {
            executable = Path.of(value.strip()).normalize();
        } catch (InvalidPathException e) {
            throw new ValidationException(tool + "_path_invalid");
        }
        if (!executable.isAbsolute()) {
            throw new ValidationException(tool + "_path_invalid");
        }
        if (!isExecutable(executable)) {
            throw new ValidationException(tool + "_path_unavailable");
        }
        return executable;
    }

    static BundledRsync.Executables findInstalled(boolean windows, String searchPath) throws ValidationException {
        Path rsync = findOnPath(windows ? "rsync.exe" : "rsync", searchPath);
        if (rsync == null) {
            throw new ValidationException("rsync_not_found");
        }
        Path ssh = findOnPath(windows ? "ssh.exe" : "ssh", searchPath);
        if (ssh == null) {
            throw new ValidationException("ssh_not_found");
        }
        return new BundledRsync.Executables(rsync, ssh);
    }

    private static Path findOnPath(String name, String searchPath) {
        if (searchPath == null) {
            return null;
        }
        for (String entry : searchPath.split(Pattern.quote(File.pathSeparator))) {
            String directory = entry.strip();
            if (directory.startsWith("\"") && directory.endsWith("\"") && directory.length() >= 2) {
                directory = directory.substring(1, directory.length() - 1);
            }
            try {
                Path candidate = Path.of(directory).resolve(name).normalize();
                // 不搜索空项或相对目录，避免意外选中工作目录中的程序。
                if (!directory.isEmpty() && candidate.isAbsolute() && isExecutable(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException ignored) {
                // 一个无效 PATH 项不影响后续有效目录。
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static boolean isExecutable(Path file) {
        return Files.isRegularFile(file) && Files.isReadable(file) && Files.isExecutable(file);
    }

    /** 预定义错误键供设置页显示，不将用户路径或命令行作为错误消息。 */
    public static final class ValidationException extends IOException {

        private final String errorKey;

        private ValidationException(String reason) {
            super(reason);
            this.errorKey = "error." + reason;
        }

        public String errorKey() {
            return errorKey;
        }

    }

}
