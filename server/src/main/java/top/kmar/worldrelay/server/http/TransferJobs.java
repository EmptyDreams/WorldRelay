package top.kmar.worldrelay.server.http;

import java.io.IOException;
import java.util.concurrent.*;

/** 大型文件任务返回 202 并轮询，避免反向代理超时；任务不占用所有权接口的线程或锁。 */
public final class TransferJobs implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final Semaphore workers = new Semaphore(2);

    private record Job(long created, Future<String> future) {

    }

    public synchronized void invalidate(String key) {
        Job job = jobs.remove(key);
        if (job != null && !job.future.isDone()) job.future.cancel(true);
    }

    public synchronized String poll(String key, Callable<String> operation) throws Exception {
        long now = System.nanoTime();
        // 传输结果包含有效期一小时的密钥，缓存提前失效，让稍后的重试能够重新签发。
        jobs.entrySet().removeIf(entry -> entry.getValue().future.isDone() && now - entry.getValue().created > TimeUnit.MINUTES.toNanos(30));
        Job job = jobs.get(key);
        if (job == null) {
            if (jobs.size() >= 128) throw new IOException("Too many transfer jobs");
            Future<String> future = executor.submit(() -> {
                workers.acquire();
                try {
                    return operation.call();
                } finally {
                    workers.release();
                }
            });
            job = new Job(now, future);
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
