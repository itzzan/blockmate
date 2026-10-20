package dev.blockmate.protocol;

/**
 * 有事发生时模组立刻发（细则第 6 节）。各 kind 用到的字段：
 * chat: from, text；death / respawn: pos, dim；kicked / disconnected: reason；
 * joined: 无；inventory_change: item, delta, count。
 */
public record Event(EventKind kind, String from, String text, Pos pos, String dim, String reason,
                    String item, Integer delta, Integer count) {

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
}
