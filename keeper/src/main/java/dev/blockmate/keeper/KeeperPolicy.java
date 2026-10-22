package dev.blockmate.keeper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 什么时候该开、什么时候该关（手册 7.2 第 2–4 条），纯逻辑：
 * 期望"在跑"而进程不在 → 重新 start，但刚 start 过要给它启动时间（startupGrace），
 * 且 10 分钟内最多 maxRestarts 次自动拉起，超过就放弃并报告；用户再次点"上线"时重新开始。
 */
public final class KeeperPolicy {

    public enum Desired { RUNNING, STOPPED }

    public enum Action { START, STOP, NONE }

    private static final long WINDOW_MS = 10 * 60_000L;

    private final int maxRestarts;
    private final long startupGraceMs;
    private Desired desired = Desired.STOPPED;
    private long lastStartMs = Long.MIN_VALUE / 2;
    private final Deque<Long> restarts = new ArrayDeque<>();
    private boolean gaveUp;

    public KeeperPolicy(int maxRestarts, long startupGraceMs) {
        this.maxRestarts = maxRestarts;
        this.startupGraceMs = startupGraceMs;
    }

    /** 后台说的期望状态（hello 带来的，或 keeper_cmd）。改成 RUNNING 时清掉"放弃"状态和重启预算。 */
    /** 收到 start（哪怕期望本来就是在跑）都重新给 10 分钟 3 次的额度：放弃之后网页再点"上线"必须能重新开始。 */
    public void setDesired(Desired d) {
        if (d == Desired.RUNNING && (desired != Desired.RUNNING || gaveUp)) {
            gaveUp = false;
            restarts.clear();
        }
        desired = d;
    }

    public Desired desired() {
        return desired;
    }

    /** 每次检查调用：进程在不在 → 要不要动手。 */
    public Action decide(boolean running, long now) {
        if (desired == Desired.STOPPED) {
            return running ? Action.STOP : Action.NONE;
        }
        if (running) {
            return Action.NONE;
        }
        if (now - lastStartMs < startupGraceMs) {
            return Action.NONE; // 刚拉起，等它启动
        }
        if (gaveUp) {
            return Action.NONE;
        }
        while (!restarts.isEmpty() && now - restarts.peekFirst() > WINDOW_MS) {
            restarts.removeFirst();
        }
        if (restarts.size() >= maxRestarts) {
            gaveUp = true;
            return Action.NONE;
        }
        restarts.addLast(now);
        return Action.START;
    }

    /** 真正执行了 start 之后调用。 */
    public void onStarted(long now) {
        lastStartMs = now;
    }

    public boolean gaveUp() {
        return gaveUp;
    }

    public int restartsInWindow(long now) {
        return (int) restarts.stream().filter(t -> now - t <= WINDOW_MS).count();
    }

    public long lastStartMs() {
        return lastStartMs;
    }
}
