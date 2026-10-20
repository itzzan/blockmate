package dev.blockmate.server.memory;

/** episodes 表的一行：某一天的日记。 */
public record Episode(String id, String day, String summary, long fromTs, long toTs, long createdTs) {
}
