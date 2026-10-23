package dev.blockmate.protocol;

import java.util.List;
import java.util.Map;

/**
 * 模组每 20 tick 发一条（细则第 5 节）。
 * "不知道"用 null 表示（JSON 里省略），主人用 owner.visible=false 表示。
 * inWorld=false 时其余字段都可以是 null。
 */
public record Snapshot(boolean inWorld, Pos pos, String dim, Double health, Integer food,
                       Map<String, Integer> inventory, OwnerInfo owner, TaskInfo task,
                       Map<String, Integer> groundItems,
                       Integer timeOfDay, Boolean isRaining, List<InventorySlot> slots) {

    /** 旧的九参数写法（没有时间 / 天气 / 逐格背包），第一阶段的代码继续用。 */
    public Snapshot(boolean inWorld, Pos pos, String dim, Double health, Integer food,
                    Map<String, Integer> inventory, OwnerInfo owner, TaskInfo task, Map<String, Integer> groundItems) {
        this(inWorld, pos, dim, health, food, inventory, owner, task, groundItems, null, null, null);
    }

    /** 不在世界里时的快照。 */
    public static Snapshot offline() {
        return new Snapshot(false, null, null, null, null, Map.of(), OwnerInfo.unknown(), null, null);
    }

    /**
     * 任务单 9 加的三个字段：timeOfDay 是世界时间 0–24000（0 日出、6000 正午、13000 入夜、18000 午夜），isRaining 是否在下雨，
     * slots 是背包逐格的内容（只列非空格；合成要知道整件物品在哪一格、有几堆）。三个都允许 null = 不知道（旧模组）。
     */
    public record InventorySlot(int slot, String item, int count) {
    }

    /** 地上物品字段是 2026-10-23 加的（JSON 键 ground_items）：伙伴 8 格内地上的掉落物，物品 id → 数量；null = 不知道（旧模组）。 */
    public Map<String, Integer> groundItemsOrNull() {
        return groundItems;
    }
}
