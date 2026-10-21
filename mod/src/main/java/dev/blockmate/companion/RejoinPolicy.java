package dev.blockmate.companion;

/** 被踢或掉线后自动重连（手册 6.4 第 7 条）：间隔 delayMs，最多 maxAttempts 次，成功进服后清零。纯逻辑。 */
public final class RejoinPolicy {

    private final long delayMs;
    private final int maxAttempts;
    private int attempts;
    private long dueAt = -1;

    public RejoinPolicy(long delayMs, int maxAttempts) {
        this.delayMs = delayMs;
        this.maxAttempts = maxAttempts;
    }

    /** 掉线了：还有次数就安排一次重连，返回 true；次数用完返回 false。 */
    public boolean onDisconnected(long nowMs) {
        if (attempts >= maxAttempts) {
            dueAt = -1;
            return false;
        }
        dueAt = nowMs + delayMs;
        return true;
    }

    /** 手动要求重连：很快就连，不受次数限制。 */
    public void scheduleIn(long nowMs, long inMs) {
        dueAt = nowMs + inMs;
    }

    public boolean due(long nowMs) {
        return dueAt > 0 && nowMs >= dueAt;
    }

    /** 真正发起一次连接时调用，返回这是第几次。 */
    public int startAttempt() {
        dueAt = -1;
        return ++attempts;
    }

    public void onJoined() {
        attempts = 0;
        dueAt = -1;
    }

    public int attempts() {
        return attempts;
    }

    public boolean pending() {
        return dueAt > 0;
    }
}
