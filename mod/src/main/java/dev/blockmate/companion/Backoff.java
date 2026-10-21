package dev.blockmate.companion;

/** 重连退避：2/4/8/16/…封顶（手册 6.2 第 4 条）。纯函数，便于测试。 */
public final class Backoff {

    private Backoff() {
    }

    /** 第 attempt 次失败（从 0 起）之后等几秒。 */
    public static int delaySeconds(int attempt, int maxSeconds) {
        long d = 2L << Math.max(0, Math.min(attempt, 20));
        return (int) Math.min(d, Math.max(1, maxSeconds));
    }
}
