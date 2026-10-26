package dev.blockmate.protocol;

import java.util.List;
import java.util.Map;

/**
 * 白名单（任务单 12，knowledge/blocks.yaml）：hub 在回模组的 hello 里下发，模组不自己读文件。
 * mineable / placeable 空清单 = 什么都不许挖、不许放（安全默认）。
 * drops（任务单 13）：方块 → 挖掉后背包里多的物品，没写的和方块同名；模组按它数"挖够了没有"。
 */
public record BlockRules(List<String> mineable, List<String> placeable, Map<String, String> drops) {

    public BlockRules(List<String> mineable, List<String> placeable) {
        this(mineable, placeable, Map.of());
    }

    public static BlockRules empty() {
        return new BlockRules(List.of(), List.of(), Map.of());
    }

    /** 老版本后台发来的 JSON 没有 drops 时为 null，这里兜成空表。 */
    public Map<String, String> dropsOrEmpty() {
        return drops == null ? Map.of() : drops;
    }

    /** 这个方块挖掉后得到的物品 id。 */
    public String dropOf(String blockId) {
        return dropsOrEmpty().getOrDefault(blockId, blockId);
    }
}
