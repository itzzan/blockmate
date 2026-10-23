package dev.blockmate.protocol;

import java.util.Map;

/**
 * 模组每 20 tick 发一条（细则第 5 节）。
 * "不知道"用 null 表示（JSON 里省略），主人用 owner.visible=false 表示。
 * inWorld=false 时其余字段都可以是 null。
 */
public record Snapshot(boolean inWorld, Pos pos, String dim, Double health, Integer food,
                       Map<String, Integer> inventory, OwnerInfo owner, TaskInfo task,
                       Map<String, Integer> groundItems) {

    /** 不在世界里时的快照。 */
    public static Snapshot offline() {
        return new Snapshot(false, null, null, null, null, Map.of(), OwnerInfo.unknown(), null, null);
    }

    /** 地上物品字段是 2026-10-23 加的（JSON 键 ground_items）：伙伴 8 格内地上的掉落物，物品 id → 数量；null = 不知道（旧模组）。 */
    public Map<String, Integer> groundItemsOrNull() {
        return groundItems;
    }
}
