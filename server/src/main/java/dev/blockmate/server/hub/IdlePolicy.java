package dev.blockmate.server.hub;

import com.google.gson.JsonArray;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 空闲循环的节流和拒绝记忆（任务单 18，guide-3 §1.3 第 1、3、4 条）。全是写死的规则，不靠模型；时间都由外面传进来，方便测。
 * 外面还要再加三个条件才算空闲：没有计划在跑、它在世界里、主人在服里、模型空着——那些要看 hub 的状态，不在这里。
 */
public final class IdlePolicy {

    /** 主人刚说完 15 秒内不主动说（避免打断）。 */
    public static final long NO_INTERRUPT_MS = 15_000;
    /** 提议之后 2 分钟内的"好 / 不"才算对这条提议说的。 */
    public static final long ACCEPT_WINDOW_MS = 120_000;
    /** 同一种提醒 5 分钟最多一次。 */
    public static final long REMIND_COOLDOWN_MS = 300_000;
    /** 被拒的建议 10 分钟内不再提。 */
    public static final long REJECT_MEMORY_MS = 600_000;
    /** 连续被拒这么多次进入"只在被问时说"，直到主人主动说一句话。 */
    public static final int QUIET_AFTER_REJECTS = 3;

    /** 一条还没被答复的提议。 */
    public record Proposal(String goal, JsonArray steps, String messageId, long ts) {
    }

    private final long intervalMs;
    private long lastOwnerMsg = Long.MIN_VALUE / 2;
    private long lastProactive = Long.MIN_VALUE / 2;
    private long lastThink = Long.MIN_VALUE / 2;
    private final Map<String, Long> remindAt = new HashMap<>();
    private final Map<String, Long> rejectedAt = new HashMap<>();
    private final Map<String, String> rejectedText = new HashMap<>();
    private int consecutiveRejects;
    private boolean quiet;
    private Proposal pending;

    public IdlePolicy(long intervalMs) {
        this.intervalMs = Math.max(1000, intervalMs);
    }

    /** 主人说了一句（任何话）：刷新时间，解除安静模式。 */
    public void ownerSpoke(long now) {
        lastOwnerMsg = now;
        if (quiet) {
            quiet = false;
            consecutiveRejects = 0;
        }
    }

    /** 时间上到没到该想一想：不在安静模式、离主人上一句和它上一次主动说话都 ≥ 间隔、离上一次想 ≥ 间隔、不在"刚说完 15 秒"里。 */
    public boolean dueToThink(long now) {
        return !quiet
                && now - lastOwnerMsg >= Math.max(intervalMs, NO_INTERRUPT_MS)
                && now - lastProactive >= intervalMs
                && now - lastThink >= intervalMs;
    }

    public void thought(long now) {
        lastThink = now;
    }

    /** 提醒类：同一 kind 5 分钟一次；允许就记下这次。 */
    public boolean allowRemind(String kind, long now) {
        Long t = remindAt.get(kind);
        if (t != null && now - t < REMIND_COOLDOWN_MS) {
            return false;
        }
        remindAt.put(kind, now);
        return true;
    }

    /** 建议类：同一个目标（按文字）被拒后 10 分钟内不再提。 */
    public boolean allowPropose(String goal, long now) {
        Long t = rejectedAt.get(goalKey(goal));
        return t == null || now - t >= REJECT_MEMORY_MS;
    }

    /** 说出去了一条提议，等主人答复。 */
    public void proposed(String goal, JsonArray steps, String messageId, long now) {
        pending = new Proposal(goal, steps, messageId, now);
        lastProactive = now;
    }

    /** 说出去了一句提醒（没有要答复的）。 */
    public void reminded(long now) {
        lastProactive = now;
    }

    /** 2 分钟内还有效的提议。 */
    public Optional<Proposal> pendingWithin(long now) {
        if (pending != null && now - pending.ts() <= ACCEPT_WINDOW_MS) {
            return Optional.of(pending);
        }
        return Optional.empty();
    }

    /** 主人点头：返回那条提议并清掉；连拒计数归零。 */
    public Proposal accepted() {
        Proposal p = pending;
        pending = null;
        consecutiveRejects = 0;
        return p;
    }

    /** 主人拒绝：记住这个目标 10 分钟不提；连拒 3 次进安静模式。返回这次是否进入了安静模式。 */
    public boolean rejected(long now) {
        if (pending != null) {
            rejectedAt.put(goalKey(pending.goal()), now);
            rejectedText.put(goalKey(pending.goal()), pending.goal());
        }
        pending = null;
        consecutiveRejects++;
        if (consecutiveRejects >= QUIET_AFTER_REJECTS) {
            quiet = true;
            return true;
        }
        return false;
    }

    /** 10 分钟内被拒过的建议原话，给"想一想"的提示用。 */
    public java.util.List<String> recentlyRejected(long now) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> e : rejectedAt.entrySet()) {
            if (now - e.getValue() < REJECT_MEMORY_MS) {
                out.add(rejectedText.getOrDefault(e.getKey(), e.getKey()));
            }
        }
        return out;
    }

    public boolean quiet() {
        return quiet;
    }

    public int consecutiveRejects() {
        return consecutiveRejects;
    }

    /** 目标文字去掉标点空格、统一小写，作为"同一个建议"的键。 */
    public static String goalKey(String goal) {
        if (goal == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        goal.codePoints().filter(Character::isLetterOrDigit).forEach(sb::appendCodePoint);
        return sb.toString().toLowerCase();
    }
}
