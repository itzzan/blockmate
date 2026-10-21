package dev.blockmate.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * 交付时怎么丢（手册 6.4 第 6 条）：整组用"丢整组"（按钮 1），零头用"丢一个"（按钮 0）逐个丢。
 * 背包格子 → 物品栏容器槽位：快捷栏 0–8 对应 36–44，主背包 9–35 对应 9–35。纯逻辑。
 */
public final class ThrowPlan {

    public record Stack(int inventoryIndex, int count) {
    }

    /** button：1 = 整组，0 = 一个。 */
    public record Click(int slot, int button) {
    }

    public record Plan(List<Click> clicks, int total) {
    }

    private ThrowPlan() {
    }

    public static int slotFor(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    public static Plan plan(List<Stack> stacks, int wanted) {
        List<Click> clicks = new ArrayList<>();
        int remaining = Math.max(0, wanted);
        for (Stack s : stacks) {
            if (remaining <= 0) {
                break;
            }
            if (s.count() <= remaining) {
                clicks.add(new Click(slotFor(s.inventoryIndex()), 1));
                remaining -= s.count();
            } else {
                for (int i = 0; i < remaining; i++) {
                    clicks.add(new Click(slotFor(s.inventoryIndex()), 0));
                }
                remaining = 0;
            }
        }
        return new Plan(clicks, Math.max(0, wanted) - remaining);
    }
}
