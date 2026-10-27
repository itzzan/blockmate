package dev.blockmate.server.memory;

/** messages 表的一行。role：owner / companion / owner_ingame / system。 */
public record StoredMessage(String id, long ts, String role, String text, String replyTo, String toolCalls, String source) {

    /** 任务单 18 之前的写法；source 为 null = 不是主动说的，"idle" = 空闲循环里它主动说的。 */
    public StoredMessage(String id, long ts, String role, String text, String replyTo, String toolCalls) {
        this(id, ts, role, text, replyTo, toolCalls, null);
    }
}
