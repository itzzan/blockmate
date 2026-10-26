package dev.blockmate.protocol;

import java.util.List;

/**
 * 白名单（任务单 12，knowledge/blocks.yaml 的 mineable / placeable 两段）：hub 在回模组的 hello 里下发，
 * 模组不自己读文件。空清单 = 什么都不许挖、不许放（安全默认）。
 */
public record BlockRules(List<String> mineable, List<String> placeable) {

    public static BlockRules empty() {
        return new BlockRules(List.of(), List.of());
    }
}
