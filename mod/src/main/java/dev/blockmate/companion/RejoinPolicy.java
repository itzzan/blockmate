package dev.blockmate.companion;

import java.util.Set;

/**
 * 被踢或掉线后自动重连（手册 6.4 第 7 条）：间隔 delayMs，最多 maxAttempts 次，成功进服后清零。纯逻辑。
 * 2026-10-22 补丁：服务器明确拒绝（不在白名单、封禁、版本不兼容、用户名问题）时不重连，重连也只会再被拒。
 */
public final class RejoinPolicy {

    /** 这些断线原因（服务器发来的翻译键，和客户端语言无关）重连没有意义。 */
    private static final Set<String> PERMANENT_REASONS = Set.of(
            "multiplayer.disconnect.not_whitelisted",
            "multiplayer.disconnect.banned",
            "multiplayer.disconnect.banned.reason",
            "multiplayer.disconnect.banned_ip.reason",
            "multiplayer.disconnect.outdated_client",
            "multiplayer.disconnect.outdated_server",
            "multiplayer.disconnect.incompatible",
            "multiplayer.disconnect.unverified_username",
            "multiplayer.disconnect.name_taken",
            "multiplayer.disconnect.invalid_player_data");

    /** reasonKey 是断线画面原因的翻译键（拿不到时为 null，当作可重试）。 */
    public static boolean isPermanent(String reasonKey) {
        return reasonKey != null && PERMANENT_REASONS.contains(reasonKey);
    }

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

    /** 放弃：取消已安排的重连（被服务器明确拒绝时用）。成功进服后 onJoined 会清零。 */
    public void cancel() {
        dueAt = -1;
        attempts = maxAttempts;
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
