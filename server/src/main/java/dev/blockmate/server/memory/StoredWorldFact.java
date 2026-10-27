package dev.blockmate.server.memory;

/**
 * 世界事实（任务单 17，world_facts 表）：它知道的地标和危险，带坐标。
 * kind ∈ village / portal_overworld / portal_nether / fortress / stronghold / end_portal / mine_site / danger；
 * source ∈ owner_said / seen / task_result；name 是主人叫它的名字（自己看见的为空）。
 */
public record StoredWorldFact(String id, String kind, String name, String dim, double x, double y, double z, long ts,
                              String source) {
}
