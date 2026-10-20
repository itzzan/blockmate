package dev.blockmate.server.memory;

/** messages 表的一行。role：owner / companion / owner_ingame / system。 */
public record StoredMessage(String id, long ts, String role, String text, String replyTo, String toolCalls) {
}
