package top.kmar.worldrelay.server.http;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 大型文件任务返回 202 并轮询，避免反向代理超时；任务不占用所有权接口的线程或锁。 */
public final class TransferJobs implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final Semaphore workers = new Semaphore(2);
    private final ConcurrentHashMap<String, Active> running = new ConcurrentHashMap<>();
    private final AtomicLong completed = new AtomicLong(), failed = new AtomicLong(), cancelled = new AtomicLong();

    @SuppressWarnings("ClassCanBeRecord") // 使用对象身份，避免取消后的迟到回调移除同参数的新任务。
    private static final class Active {

        final String world, username, operation;

        Active(String world, String username, String operation) {
            this.world = world;
            this.username = username;
            this.operation = operation;
        }

    }

    private record Job(long created, Active description, Future<String> future) {

    }

    public synchronized void invalidate(String key) {
        Job job = jobs.remove(key);
        if (job != null && !job.future.isDone()) job.future.cancel(true);
    }

    public synchronized void invalidateUser(String username) {
        for (var entry : List.copyOf(jobs.entrySet())) {
            if (Objects.equals(entry.getValue().description.username, username)) invalidate(entry.getKey());
        }
    }

    public synchronized void invalidateWorld(String world) {
        for (var entry : List.copyOf(jobs.entrySet())) {
            if (Objects.equals(entry.getValue().description.world, world)) invalidate(entry.getKey());
        }
    }

    public record Stats(int queued, int running, int cached, long completed, long failed, long cancelled,
                        List<String> activeOperations) {

    }

    public synchronized Stats stats(String world) {
        var selected = jobs.entrySet().stream().filter(entry -> world == null || Objects.equals(world, entry.getValue().description.world)).toList();
        int queued = (int) selected.stream().filter(entry -> !entry.getValue().future.isDone() && !running.containsKey(entry.getKey())).count();
        int active = (int) running.values().stream().filter(value -> world == null || Objects.equals(world, value.world)).count();
        int cached = (int) selected.stream().filter(entry -> entry.getValue().future.isDone()).count();
        var operations = java.util.stream.Stream.concat(
                selected.stream().filter(entry -> !entry.getValue().future.isDone()).map(entry -> entry.getValue().description.operation),
                running.values().stream().filter(value -> world == null || Objects.equals(world, value.world)).map(value -> value.operation))
            .distinct().sorted().toList();
        return new Stats(queued, active, cached, completed.get(), failed.get(), cancelled.get(), operations);
    }

    public synchronized String poll(String key, String world, String username, String kind, Callable<String> operation) throws Exception {
        long now = System.nanoTime();
        // 传输结果包含有效期一小时的密钥，缓存提前失效，让稍后的重试能够重新签发。
        jobs.entrySet().removeIf(entry -> entry.getValue().future.isDone() && now - entry.getValue().created > TimeUnit.MINUTES.toNanos(30));
        Job job = jobs.get(key);
        if (job == null) {
            if (jobs.size() >= 128) throw new IOException("Too many transfer jobs");
            Active description = new Active(world, username, kind);
            Future<String> future = executor.submit(() -> {
                boolean acquired = false;
                try {
                    workers.acquire();
                    acquired = true;
                    running.put(key, description);
                    String result = operation.call();
                    completed.incrementAndGet();
                    return result;
                } catch (Exception | Error failure) {
                    if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) cancelled.incrementAndGet();
                    else failed.incrementAndGet();
                    throw failure;
                } finally {
                    running.remove(key, description);
                    if (acquired) workers.release();
                }
            });
            job = new Job(now, description, future);
            jobs.put(key, job);
        }
        if (!job.future.isDone()) return null;
        try {
            return job.future.get();
        } catch (ExecutionException failure) {
            jobs.remove(key, job);
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw new IOException("Snapshot job failed");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

}
