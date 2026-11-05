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

    /** 验证名称相似的 -munge-links 选项不会被误判为安全链接隔离能力。 */
    @Test
    void doesNotAcceptSimilarMungeOptionNames() throws Exception {
        Path program = script("printf '%s\\n' 'usage: rrsync [-ro|-wo] [-munge-links] DIR'\n");
        assertFalse(RrsyncCapabilities.supportsMunge(program.toString()));
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

    private Path script(String body) throws IOException {
        Path program = Files.writeString(directory.resolve("rrsync"), "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return program;
    }

}
