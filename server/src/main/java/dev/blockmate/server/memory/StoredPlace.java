package dev.blockmate.server.memory;

/** 记过的地点（任务单 13，places 表）：home / bed / 主人起的名字。 */
public record StoredPlace(String name, String dim, double x, double y, double z, long ts) {
}
