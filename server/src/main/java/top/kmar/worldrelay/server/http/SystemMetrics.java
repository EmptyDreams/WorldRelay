package top.kmar.worldrelay.server.http;

import top.kmar.worldrelay.server.json.JsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Linux 只读资源采样；首次速率和不可读指标为 null，不依赖外部进程、JMX 或反射。 */
final class SystemMetrics {

    record Cpu(Long logicalProcessors, long availableProcessors, Long systemUsagePermille,
               Long processUsagePermille, Long loadAverageMilli) {

    }

    record Memory(Long totalBytes, Long availableBytes, Long processRssBytes) {

    }

    record Network(Long receivedBytes, Long sentBytes, Long receiveBytesPerSecond, Long sendBytesPerSecond) {

    }

    record Snapshot(Cpu cpu, Memory memory, Network network) {

    }

    private record CpuTicks(long total, long idle, long cores) {

    }

    private record Traffic(long received, long sent) {

    }

    private record Sample(long at, CpuTicks cpu, Long process, Map<String, Traffic> traffic) {

    }

    private final Path proc;
    private final LongSupplier clock;
    private Sample previous;
    private Snapshot cached;

    SystemMetrics() {
        this(System.getProperty("os.name", "").startsWith("Linux") ? Path.of("/proc") : null, System::nanoTime);
    }

    SystemMetrics(Path proc, LongSupplier clock) {
        this.proc = proc;
        this.clock = clock;
    }

    synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        long elapsed = previous == null ? 0 : now - previous.at;
        if (cached != null && elapsed >= 0 && elapsed < TimeUnit.SECONDS.toNanos(1)) return cached;
        CpuTicks cpu = cpu(read("stat"));
        Long process = processTicks(read("self/stat"));
        Map<String, Traffic> traffic = traffic(read("net/dev"));
        Long systemUsage = null, processUsage = null;
        if (previous != null && elapsed > 0 && previous.cpu != null && cpu != null) {
            long totalDelta = cpu.total - previous.cpu.total;
            long idleDelta = cpu.idle - previous.cpu.idle;
            if (totalDelta > 0 && idleDelta >= 0 && idleDelta <= totalDelta) {
                systemUsage = permille(totalDelta - idleDelta, totalDelta);
                if (process != null && previous.process != null && process >= previous.process) {
                    processUsage = permille(process - previous.process, totalDelta);
                }
            }
        }
        Long received = null, sent = null, receiveRate = null, sendRate = null;
        if (traffic != null) {
            try {
                long rx = 0, tx = 0;
                for (Traffic item : traffic.values()) {
                    rx = Math.addExact(rx, item.received);
                    tx = Math.addExact(tx, item.sent);
                }
                received = rx;
                sent = tx;
                if (previous != null && elapsed > 0 && previous.traffic != null && traffic.keySet().equals(previous.traffic.keySet())) {
                    long rxDelta = 0, txDelta = 0;
                    boolean valid = true;
                    for (var entry : traffic.entrySet()) {
                        Traffic before = previous.traffic.get(entry.getKey()), current = entry.getValue();
                        if (current.received < before.received || current.sent < before.sent) {
                            valid = false;
                            break;
                        }
                        rxDelta = Math.addExact(rxDelta, current.received - before.received);
                        txDelta = Math.addExact(txDelta, current.sent - before.sent);
                    }
                    if (valid) {
                        receiveRate = rate(rxDelta, elapsed);
                        sendRate = rate(txDelta, elapsed);
                    }
                }
            } catch (ArithmeticException ignored) { /* 计数溢出时不生成错误速率。 */ }
        }
        String memory = read("meminfo");
        Long total = kib(memory, "MemTotal"), available = kib(memory, "MemAvailable");
        if (total != null && available != null && available > total) available = null;
        cached = new Snapshot(new Cpu(cpu == null ? null : cpu.cores, Runtime.getRuntime().availableProcessors(),
            systemUsage, processUsage, load(read("loadavg"))),
            new Memory(total, available, kib(read("self/status"), "VmRSS")),
            new Network(received, sent, receiveRate, sendRate));
        previous = new Sample(now, cpu, process, traffic);
        return cached;
    }

    void write(JsonWriter writer) {
        Snapshot sample = snapshot();
        writer.beginObject().name("cpu").beginObject();
        number(writer, "logicalProcessors", sample.cpu.logicalProcessors);
        writer.name("availableProcessors").value(sample.cpu.availableProcessors);
        number(writer, "systemUsagePermille", sample.cpu.systemUsagePermille);
        number(writer, "processUsagePermille", sample.cpu.processUsagePermille);
        number(writer, "loadAverageMilli", sample.cpu.loadAverageMilli);
        writer.endObject().name("memory").beginObject();
        number(writer, "totalBytes", sample.memory.totalBytes);
        number(writer, "availableBytes", sample.memory.availableBytes);
        number(writer, "processRssBytes", sample.memory.processRssBytes);
        writer.endObject().name("network").beginObject();
        number(writer, "receivedBytes", sample.network.receivedBytes);
        number(writer, "sentBytes", sample.network.sentBytes);
        number(writer, "receiveBytesPerSecond", sample.network.receiveBytesPerSecond);
        number(writer, "sendBytesPerSecond", sample.network.sendBytesPerSecond);
        writer.endObject().endObject();
    }

    private static void number(JsonWriter writer, String key, Long value) {
        writer.name(key);
        if (value == null) writer.nullValue();
        else writer.value(value);
    }

    private String read(String relative) {
        if (proc == null) return null;
        try (var input = Files.newInputStream(proc.resolve(relative))) {
            byte[] data = input.readNBytes(1024 * 1024 + 1);
            return data.length > 1024 * 1024 ? null : new String(data, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static long positive(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) throw new IllegalArgumentException("Negative kernel counter");
        return parsed;
    }

    private static CpuTicks cpu(String contents) {
        if (contents == null) return null;
        try {
            long total = 0, idle = 0, cores = 0;
            boolean found = false;
            for (String line : contents.lines().toList()) {
                String[] fields = line.strip().split("\\s+");
                if (fields[0].equals("cpu")) {
                    if (fields.length < 5) return null;
                    // guest 和 guest_nice 已计入 user/nice，只求前八种时间之和。
                    for (int i = 1; i < Math.min(fields.length, 9); i++) {
                        total = Math.addExact(total, positive(fields[i]));
                    }
                    idle = positive(fields[4]);
                    if (fields.length > 5) idle = Math.addExact(idle, positive(fields[5]));
                    found = true;
                } else if (fields[0].matches("cpu[0-9]+")) cores++;
            }
            return found && cores > 0 ? new CpuTicks(total, idle, cores) : null;
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return null;
        }
    }

    private static Long processTicks(String contents) {
        if (contents == null) return null;
        try {
            int end = contents.lastIndexOf(')');
            if (end < 0) return null;
            String[] fields = contents.substring(end + 1).strip().split("\\s+");
            return fields.length < 13 ? null : Math.addExact(positive(fields[11]), positive(fields[12]));
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return null;
        }
    }

    private static Map<String, Traffic> traffic(String contents) {
        if (contents == null) return null;
        try {
            Map<String, Traffic> result = new HashMap<>();
            for (String line : contents.lines().toList()) {
                int separator = line.indexOf(':');
                if (separator < 0) continue;
                String name = line.substring(0, separator).strip();
                if (name.equals("lo")) continue;
                String[] fields = line.substring(separator + 1).strip().split("\\s+");
                if (name.isEmpty() || fields.length < 16) return null;
                result.put(name, new Traffic(positive(fields[0]), positive(fields[8])));
            }
            return Map.copyOf(result);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Long kib(String contents, String key) {
        if (contents == null) return null;
        try {
            for (String line : contents.lines().toList()) {
                if (!line.startsWith(key + ":")) continue;
                String[] fields = line.substring(key.length() + 1).strip().split("\\s+");
                if (fields.length != 2 || !fields[1].equals("kB")) return null;
                return Math.multiplyExact(positive(fields[0]), 1024);
            }
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return null;
        }
        return null;
    }

    private static Long load(String contents) {
        if (contents == null) return null;
        try {
            double value = Double.parseDouble(contents.strip().split("\\s+")[0]);
            return Double.isFinite(value) && value >= 0 && value < Long.MAX_VALUE / 1000.0 ? Math.round(value * 1000) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long permille(long used, long total) {
        return Math.round(Math.clamp((double) used / total, 0, 1) * 1000);
    }

    private static Long rate(long bytes, long nanos) {
        double value = bytes * (1_000_000_000.0 / nanos);
        return Double.isFinite(value) && value < Long.MAX_VALUE ? Math.round(value) : null;
    }

}
