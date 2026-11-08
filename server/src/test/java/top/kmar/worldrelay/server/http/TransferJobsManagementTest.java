package top.kmar.worldrelay.server.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TransferJobsManagementTest {

    /** 验证队列与执行数量、按世界筛选，以及禁用用户和删除世界时的任务撤销。 */
    @SuppressWarnings("BusyWait") // 有截止时间的异步状态轮询，等待真实后台线程完成。
    @Test
    void reportsAndInvalidatesScopedBackgroundOperations() throws Exception {
        try (var jobs = new TransferJobs()) {
            var started = new CountDownLatch(2);
            var waiting = new CountDownLatch(1);
            jobs.poll("alice", "world", "Alice", "UPLOAD", () -> {
                started.countDown();
                waiting.await();
                return "alice";
            });
            jobs.poll("bob", "world", "Bob", "DOWNLOAD", () -> {
                started.countDown();
                waiting.await();
                return "bob";
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            jobs.poll("queued", "world", "Bob", "UPLOAD", () -> "queued");
            var before = jobs.stats("world");
            assertEquals(2, before.running());
            assertEquals(1, before.queued());
            assertEquals(List.of("DOWNLOAD", "UPLOAD"), before.activeOperations());
            assertEquals(0, jobs.stats("other").running());
            jobs.invalidateUser("Alice");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((jobs.stats("world").running() != 1 || jobs.stats("world").cached() != 1) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, jobs.stats("world").running());
            assertEquals(1, jobs.stats("world").cached());
            assertTrue(jobs.stats(null).cancelled() >= 1);
            jobs.invalidateWorld("world");
            while (jobs.stats("world").running() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(0, jobs.stats("world").running());
            assertEquals(0, jobs.stats("world").queued());
            assertEquals(0, jobs.stats("world").cached());
        }
    }

    /** 已取消任务的迟到完成回调不能移除同一请求重新启动的新任务状态。 */
    @Test
    void lateCancelledOperationCannotEraseItsReplacement() throws Exception {
        var oldRelease = new CountDownLatch(1);
        var newRelease = new CountDownLatch(1);
        try (var jobs = new TransferJobs()) {
            var oldEntered = new CountDownLatch(1);
            var newEntered = new CountDownLatch(1);
            var oldThread = new AtomicReference<Thread>();
            try {
                jobs.poll("same", "world", "Alice", "UPLOAD", () -> {
                    oldThread.set(Thread.currentThread());
                    oldEntered.countDown();
                    boolean finished = false;
                    while (!finished) {
                        try {
                            oldRelease.await();
                            finished = true;
                        } catch (InterruptedException ignored) { /* 模拟取消后才结束的底层 IO。 */ }
                    }
                    return "old";
                });
                assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
                jobs.invalidateUser("Alice");
                jobs.poll("same", "world", "Alice", "UPLOAD", () -> {
                    newEntered.countDown();
                    newRelease.await();
                    return "new";
                });
                assertTrue(newEntered.await(5, TimeUnit.SECONDS));
                oldRelease.countDown();
                oldThread.get().join(5000);
                assertFalse(oldThread.get().isAlive());
                assertEquals(1, jobs.stats("world").running());
                assertEquals(0, jobs.stats("world").queued());
            } finally {
                oldRelease.countDown();
                newRelease.countDown();
            }
        }
    }

}