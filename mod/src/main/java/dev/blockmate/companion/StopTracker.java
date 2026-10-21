package dev.blockmate.companion;

/** stop 的"真的停了才算"（手册 6.4 第 4 条）：静止才 ok；每 ticksPerAttempt 没静止再取消一次，最多 maxAttempts 次。纯逻辑。 */
public final class StopTracker {

    public enum Verdict { WAIT, RETRY, DONE_STATIONARY, GIVE_UP }

    private final int ticksPerAttempt;
    private final int maxAttempts;
    private int attempts = 1;
    private int attemptStartTick;

    public StopTracker(int ticksPerAttempt, int maxAttempts, int startTick) {
        this.ticksPerAttempt = ticksPerAttempt;
        this.maxAttempts = maxAttempts;
        this.attemptStartTick = startTick;
    }

    public Verdict feed(boolean stationary, int tick) {
        if (stationary) {
            return Verdict.DONE_STATIONARY;
        }
        if (tick - attemptStartTick >= ticksPerAttempt) {
            if (attempts >= maxAttempts) {
                return Verdict.GIVE_UP;
            }
            attempts++;
            attemptStartTick = tick;
            return Verdict.RETRY;
        }
        return Verdict.WAIT;
    }

    public int attempts() {
        return attempts;
    }
}
