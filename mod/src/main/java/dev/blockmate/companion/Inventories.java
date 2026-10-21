package dev.blockmate.companion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 背包摘要的比对（手册 6.3 第 2 条"某种物品数量变化"），纯逻辑。 */
public final class Inventories {

    public record Change(String item, int delta, int count) {
    }

    private Inventories() {
    }

    /** prev → now 之间每种物品的变化；新出现为正，消失为负。 */
    public static List<Change> diff(Map<String, Integer> prev, Map<String, Integer> now) {
        List<Change> out = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(prev.keySet());
        keys.addAll(now.keySet());
        for (String k : keys) {
            int a = prev.getOrDefault(k, 0);
            int b = now.getOrDefault(k, 0);
            if (a != b) {
                out.add(new Change(k, b - a, b));
            }
        }
        return out;
    }

    /** 把 (id, count) 累加进摘要，忽略空的。 */
    public static void add(Map<String, Integer> summary, String id, int count) {
        if (id == null || count <= 0) {
            return;
        }
        summary.merge(id, count, Integer::sum);
    }

    public static Map<String, Integer> empty() {
        return new LinkedHashMap<>();
    }
}
