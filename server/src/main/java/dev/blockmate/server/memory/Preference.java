package dev.blockmate.server.memory;

/** preferences 表的一行。status：pending / active / ignored。 */
public record Preference(String id, long ts, String text, String status, String sourceMessageId, Long decidedTs) {
}
