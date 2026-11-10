package top.kmar.worldrelay.server.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SystemMetricsTest {

    @TempDir
    Path proc;

    private void sample(long user, long idle, long process, long received, long sent) throws Exception {
        Files.createDirectories(proc.resolve("self"));
        Files.createDirectories(proc.resolve("net"));
        Files.writeString(proc.resolve("stat"), "cpu " + user + " 0 100 " + idle + " 0 0 0 0 " + user / 2 + " " + user / 4 + "\ncpu0 0 0 0 0\ncpu1 0 0 0 0\n");
        // comm 中带空格和右括号；utime/stime 仍取括号结束后的固定字段。
        Files.writeString(proc.resolve("self/stat"), "42 (worker ) sample) R" + " 0".repeat(10) + " " + process + " 0\n");
        Files.writeString(proc.resolve("meminfo"), "MemTotal: 2048 kB\nMemAvailable: 512 kB\n");
        Files.writeString(proc.resolve("self/status"), "Name: fixture\nVmRSS: 128 kB\n");
        Files.writeString(proc.resolve("loadavg"), "1.25 2.00 3.00 1/10 42\n");
        Files.writeString(proc.resolve("net/dev"), "Inter-| Receive | Transmit\n face |bytes packets\n"
            + "lo: 9000000 0 0 0 0 0 0 0 9000000 0 0 0 0 0 0 0\n"
            + "eth0: " + received + " 0 0 0 0 0 0 0 " + sent + " 0 0 0 0 0 0 0\n");
    }

    /** 验证 CPU 差值、内存单位和两次采样的网络速率；guest 时间及回环流量不重复计入。 */
    @Test
    void samplesCpuMemoryAndNetworkWithoutWaiting() throws Exception {
        var clock = new AtomicLong();
        var metrics = new SystemMetrics(proc, clock::get);
        sample(100, 800, 50, 1000, 2000);
        var first = metrics.snapshot();
        assertNull(first.cpu().systemUsagePermille());
        assertNull(first.network().receiveBytesPerSecond());
        assertEquals(1000L, first.network().receivedBytes());
        assertEquals(2L, first.cpu().logicalProcessors());
        assertTrue(first.cpu().availableProcessors() > 0);
        assertEquals(2048L * 1024, first.memory().totalBytes());
        assertEquals(512L * 1024, first.memory().availableBytes());
        assertEquals(128L * 1024, first.memory().processRssBytes());
        assertEquals(1250L, first.cpu().loadAverageMilli());
        sample(300, 1000, 90, 3000, 6000);
        clock.set(TimeUnit.MILLISECONDS.toNanos(500));
        assertSame(first, metrics.snapshot());
        clock.set(TimeUnit.SECONDS.toNanos(2));
        var second = metrics.snapshot();
        assertEquals(500L, second.cpu().systemUsagePermille());
        assertEquals(100L, second.cpu().processUsagePermille());
        assertEquals(1000L, second.network().receiveBytesPerSecond());
        assertEquals(2000L, second.network().sendBytesPerSecond());
        assertEquals(6000L, second.network().sentBytes());
    }

    /** 验证计数重置、网卡变化和部分文件不可读时不生成负速率，也不影响仍可取得的指标。 */
    @Test
    void rebasesResetCountersAndKeepsIndependentMetricsAvailable() throws Exception {
        var clock = new AtomicLong();
        var metrics = new SystemMetrics(proc, clock::get);
        sample(100, 800, 50, 1000, 2000);
        metrics.snapshot();
        sample(10, 80, 5, 10, 20);
        Files.delete(proc.resolve("meminfo"));
        clock.set(TimeUnit.SECONDS.toNanos(2));
        var reset = metrics.snapshot();
        assertNull(reset.cpu().systemUsagePermille());
        assertNull(reset.network().receiveBytesPerSecond());
        assertNull(reset.memory().totalBytes());
        assertEquals(128L * 1024, reset.memory().processRssBytes());
        sample(300, 1000, 90, 3000, 6000);
        Path network = proc.resolve("net/dev");
        Files.writeString(network, Files.readString(network).replace("eth0:", "eth9:"));
        clock.set(TimeUnit.SECONDS.toNanos(4));
        var replaced = metrics.snapshot();
        assertNull(replaced.network().receiveBytesPerSecond());
        assertEquals(3000L, replaced.network().receivedBytes());
        Files.writeString(proc.resolve("loadavg"), "NaN\n");
        Files.delete(proc.resolve("stat"));
        clock.set(TimeUnit.SECONDS.toNanos(6));
        var partial = metrics.snapshot();
        assertNull(partial.cpu().logicalProcessors());
        assertNull(partial.cpu().loadAverageMilli());
        assertTrue(partial.cpu().availableProcessors() > 0);
        assertEquals(2048L * 1024, partial.memory().totalBytes());
    }

}