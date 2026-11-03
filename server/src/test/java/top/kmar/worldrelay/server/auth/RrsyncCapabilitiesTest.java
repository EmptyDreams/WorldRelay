package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@Timeout(10)
class RrsyncCapabilitiesTest {

    @TempDir
    Path directory;

    /** 验证标准错误上的帮助文本也能识别 -munge，输出超过管道容量时仍能及时排空并结束探测。 */
    @Test
    void detectsMungeAndDrainsLargeHelpOutput() throws Exception {
        Path program = script("""
            printf '%s\\n' 'usage: rrsync [-munge] DIR' >&2
            head -c 131072 /dev/zero
            """);
        assertTrue(RrsyncCapabilities.supportsMunge(program.toString()));
    }

    /** 验证旧版帮助只输出一条省略参数的日志，且 -munge-links 之类的其他选项不能被误判为支持。 */
    @Test
    void unsupportedOptionIsOmittedWithOneLogMessage() throws Exception {
        Path program = script("printf '%s\\n' 'usage: rrsync [-ro|-wo] [-munge-links] DIR'\n");
        List<LogRecord> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(RrsyncCapabilities.class.getName());
        logger.addHandler(capture);
        try {
            assertFalse(RrsyncCapabilities.supportsMunge(program.toString()));
            assertEquals(1, messages.size());
            assertTrue(messages.getFirst().getMessage().contains("omit"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    /** 验证程序缺失和帮助命令非零退出均可回退，不会因错误文本中出现 -munge 而启用参数或抛错。 */
    @Test
    void missingProgramAndUnsuccessfulHelpFallBackWithoutFailure() throws Exception {
        assertFalse(RrsyncCapabilities.supportsMunge(directory.resolve("missing-rrsync").toString()));
        Path program = script("printf '%s\\n' 'unknown option: -munge'\nexit 1\n");
        assertFalse(RrsyncCapabilities.supportsMunge(program.toString()));
    }

    /** 验证帮助命令卡住时按时回退，并终止探测进程，避免服务启动无限等待或残留后台进程。 */
    @Test
    void timesOutAndTerminatesHelpProcess() throws Exception {
        Path program = script("printf '%s\\n' \"$$\" > \"$0.pid\"\nexec sleep 30\n");
        assertFalse(RrsyncCapabilities.supportsMunge(program.toString(), Duration.ofMillis(300)));
        long pid = Long.parseLong(Files.readString(Path.of(program + ".pid")).strip());
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        if (process != null) process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true));
    }

    /** 验证每次启动只探测一次，后续注册复用能力；重启后能力变化会应用到覆盖注册的公钥行。 */
    @Test
    void cachesCapabilityForRegistrationsAndRechecksAfterRestart() throws Exception {
        Path program = script("printf '%s\\n' \"$*\" >> \"$0.calls\"\nprintf '%s\\n' 'rrsync [-munge] DIR'\n");
        Path callsFile = Path.of(program + ".calls");
        Path file = directory.resolve("users.json");
        var ssh = new SshRegistrationConfig(directory.resolve("authorized_keys"), directory.resolve("transfers"), program.toString());
        try (var users = UserStore.open(file, ssh)) {
            users.register("Dev", false);
            users.register("Alice", false);
            assertTrue(Files.readString(ssh.authorizedKeysFile()).contains(" -munge "));
            assertEquals(List.of("-help"), Files.readAllLines(callsFile));
        }
        script("printf '%s\\n' \"$*\" >> \"$0.calls\"\nprintf '%s\\n' 'rrsync [-ro|-wo] DIR'\n");
        try (var users = UserStore.open(file, ssh)) {
            users.register("Dev", true);
            String dev = Files.readString(ssh.authorizedKeysFile()).lines().filter(line -> line.endsWith(" Dev")).findFirst().orElseThrow();
            assertFalse(dev.contains(" -munge "));
            assertTrue(dev.startsWith("restrict,command=\""));
            assertEquals(List.of("-help", "-help"), Files.readAllLines(callsFile));
        }
    }

    private Path script(String body) throws IOException {
        Path program = Files.writeString(directory.resolve("rrsync"), "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return program;
    }

}
