package dev.blockmate.protocol;

import java.util.List;
import java.util.Map;

/**
 * 知识层下发给模组的规则（任务单 12–15，knowledge/ 目录）：hub 在回模组的 hello 里下发，模组不自己读文件。
 * mineable / placeable 空清单 = 什么都不许挖、不许放（安全默认）。
 * drops（任务单 13）：方块 → 挖掉后背包里多的物品，没写的和方块同名。
 * avoidFood（任务单 15）：不该吃的食物，只有没别的可吃时才碰。
 */
public record BlockRules(List<String> mineable, List<String> placeable, Map<String, String> drops, List<String> avoidFood) {

    public BlockRules(List<String> mineable, List<String> placeable) {
        this(mineable, placeable, Map.of(), List.of());
    }

    public BlockRules(List<String> mineable, List<String> placeable, Map<String, String> drops) {
        this(mineable, placeable, drops, List.of());
    }

    public static BlockRules empty() {
        return new BlockRules(List.of(), List.of(), Map.of(), List.of());
    }

    /** 老版本后台发来的 JSON 没有 drops 时为 null，这里兜成空表。 */
    public Map<String, String> dropsOrEmpty() {
        return drops == null ? Map.of() : drops;
    }

    public List<String> avoidFoodOrEmpty() {
        return avoidFood == null ? List.of() : avoidFood;
    }

    /** 这个方块挖掉后得到的物品 id。 */
    public String dropOf(String blockId) {
        return dropsOrEmpty().getOrDefault(blockId, blockId);
    }
}
