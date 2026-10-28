package dev.blockmate.protocol;

/**
 * 有事发生时模组立刻发（细则第 6 节）。各 kind 用到的字段：
 * chat: from, text；death / respawn: pos, dim；kicked / disconnected: reason；
 * joined: 无；inventory_change: item, delta, count；auto_eat: item, count（吃完的饥饿值）；bed_collected: pos, dim；
 * landmark / danger（任务单 17）: what, pos, dim。
 */
public record Event(EventKind kind, String from, String text, Pos pos, String dim, String reason,
                    String item, Integer delta, Integer count, String what) {

    /** 任务单 17 之前的九参数写法。 */
    public Event(EventKind kind, String from, String text, Pos pos, String dim, String reason,
                 String item, Integer delta, Integer count) {
        this(kind, from, text, pos, dim, reason, item, delta, count, null);
    }

    public static Event chat(String from, String text) {
        return new Event(EventKind.CHAT, from, text, null, null, null, null, null, null);
    }

    public static Event death(Pos pos, String dim) {
        return new Event(EventKind.DEATH, null, null, pos, dim, null, null, null, null);
    }

    public static Event respawn(Pos pos, String dim) {
        return new Event(EventKind.RESPAWN, null, null, pos, dim, null, null, null, null);
    }

    public static Event kicked(String reason) {
        return new Event(EventKind.KICKED, null, null, null, null, reason, null, null, null);
    }

    public static Event disconnected(String reason) {
        return new Event(EventKind.DISCONNECTED, null, null, null, null, reason, null, null, null);
    }

    public static Event joined() {
        return new Event(EventKind.JOINED, null, null, null, null, null, null, null, null);
    }

    public static Event inventoryChange(String item, int delta, int count) {
        return new Event(EventKind.INVENTORY_CHANGE, null, null, null, null, null, item, delta, count);
    }

    /** 任务单 15：自动吃了一口；count 放吃完后的饥饿值。 */
    public static Event autoEat(String item, int foodAfter) {
        return new Event(EventKind.AUTO_EAT, null, null, null, null, null, item, null, foodAfter);
    }

    /** 任务单 15：睡醒后收回了自己放的床。 */
    public static Event bedCollected(Pos pos, String dim) {
        return new Event(EventKind.BED_COLLECTED, null, null, pos, dim, null, null, null, null);
    }

    /** 任务单 17：看见了一个地标。what 是地标种类，pos 是地标（不是自己）的位置。 */
    public static Event landmark(String what, Pos pos, String dim) {
        return new Event(EventKind.LANDMARK, null, null, pos, dim, null, null, null, null, what);
    }

    /** 任务单 17：看见了危险（第一版只有 lava）；任务单 19：what = overwhelmed（打不过了，正往主人跑）。 */
    public static Event danger(String what, Pos pos, String dim) {
        return new Event(EventKind.DANGER, null, null, pos, dim, null, null, null, null, what);
    }

    /** 任务单 19：打完一架。借用字段：what = 对手种类 id，reason = 结局，count = 用时毫秒，delta = 掉的血。 */
    public static Event combat(String targetKind, String outcome, int ms, int damageTaken) {
        return new Event(EventKind.COMBAT, null, null, null, null, outcome, null, damageTaken, ms, targetKind);
    }
}
