package top.kmar.mc.worldreplay.client;

import top.kmar.mc.worldreplay.sync.RsyncTransfer;

import java.util.concurrent.TimeUnit;

/** 每次同步独享一份状态，后台发布快照，渲染线程只读取。 */
final class WorldTransferProgress {
    enum Phase { PREPARING, TRANSFERRING, VERIFYING, PUBLISHING }

    record State(Phase phase, RsyncTransfer.Progress sample, long updatedAt) {
        String currentRate() {
            return sample != null && System.nanoTime() - updatedAt < TimeUnit.SECONDS.toNanos(3) ? sample.rate() : "—";
        }
    }

    private volatile State state = new State(Phase.PREPARING, null, 0);

    State state() {
        return state;
    }

    void phase(Phase phase) {
        state = new State(phase, null, 0);
    }

    void update(RsyncTransfer.Progress sample) {
        state = new State(Phase.TRANSFERRING, sample, System.nanoTime());
    }
}
