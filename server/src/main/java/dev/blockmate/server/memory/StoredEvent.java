package dev.blockmate.server.memory;

/** events 表的一行。payload 是 JSON 文本。 */
public record StoredEvent(String id, long ts, String kind, String commandId, String sourceMessageId, String payload) {
}
