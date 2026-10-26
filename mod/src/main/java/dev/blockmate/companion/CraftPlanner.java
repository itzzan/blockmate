package dev.blockmate.companion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合成的纯逻辑部分（任务单 14）：配方的每个格子能放哪些物品 + 背包里有什么 → 每个格子放什么、缺什么。
 * 和游戏无关，可单测。真正的点击由 Executor 按这里的分配去发包。
 */
public final class CraftPlanner {

    /** 一个格子可接受的物品 id 集合；空集合 = 这个格子留空。 */
    public record Cell(Set<String> accepts) {
        public boolean empty() {
            return accepts == null || accepts.isEmpty();
        }
    }

    /**
     * @param assignment 每个格子放的物品 id（留空的格子为 null），长度 = 格子数
     * @param missing    缺的物品及数量（空表示材料够）
     */
    public record Plan(List<String> assignment, Map<String, Integer> missing) {
        public boolean ok() {
            return missing.isEmpty();
        }
    }

    private CraftPlanner() {
    }

    /** 贪心：每个格子按接受列表的顺序挑背包里还有的；都没有就记一次缺（记接受列表里的第一个）。 */
    public static Plan plan(List<Cell> cells, Map<String, Integer> inventory) {
        Map<String, Integer> left = new LinkedHashMap<>();
        if (inventory != null) {
            left.putAll(inventory);
        }
        List<String> assignment = new ArrayList<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Cell c : cells) {
            if (c == null || c.empty()) {
                assignment.add(null);
                continue;
            }
            String chosen = null;
            for (String id : c.accepts()) {
                if (left.getOrDefault(id, 0) > 0) {
                    chosen = id;
                    break;
                }
            }
            if (chosen == null) {
                String first = c.accepts().iterator().next();
                missing.merge(first, 1, Integer::sum);
                assignment.add(null);
            } else {
                left.merge(chosen, -1, Integer::sum);
                assignment.add(chosen);
            }
        }
        return new Plan(assignment, missing);
    }

    /**
     * 把 w×h 的配方铺到 gridW×gridH 的格子里（左上对齐），返回按格子顺序排列的 Cell；配方比格子大返回 null。
     * @param shaped 按行优先排列的配方格子（长度 = w*h）
     */
    public static List<Cell> layout(List<Cell> shaped, int w, int h, int gridW, int gridH) {
        if (w > gridW || h > gridH || shaped.size() != w * h) {
            return null;
        }
        List<Cell> out = new ArrayList<>();
        for (int y = 0; y < gridH; y++) {
            for (int x = 0; x < gridW; x++) {
                out.add(x < w && y < h ? shaped.get(y * w + x) : new Cell(Set.of()));
            }
        }
        return out;
    }

    /** 无形状配方：材料按顺序填进前几个格子。 */
    public static List<Cell> layoutShapeless(List<Cell> ingredients, int gridW, int gridH) {
        if (ingredients.size() > gridW * gridH) {
            return null;
        }
        List<Cell> out = new ArrayList<>(ingredients);
        while (out.size() < gridW * gridH) {
            out.add(new Cell(Set.of()));
        }
        return out;
    }

    /** 要 wanted 个成品、一份出 perCraft 个 → 做几份。 */
    public static int batches(int wanted, int perCraft) {
        int per = Math.max(1, perCraft);
        return Math.max(1, (Math.max(1, wanted) + per - 1) / per);
    }
}
