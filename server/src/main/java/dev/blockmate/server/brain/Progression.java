package dev.blockmate.server.brain;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 通关地图（任务单 16，guide-3 §1.1）：knowledge/progression.yaml 的数据 + "我们在哪个阶段、缺什么"的纯逻辑。
 * 不碰模型、不碰数据库；文件怎么读、何时热重载在 {@link Knowledge#progression()}。
 *
 * 算法：从第一个阶段往后看，needs 全满足（只算背包）且 facts 全有的算做完；后面的做完了前面的也算做完；
 * 记过的阶段（progress 表，任务单 17）不退回；第一个没做完的就是当前阶段，它没满足的 needs / facts 就是"缺什么"。
 */
public final class Progression {

    /** 一条目标：item（单个物品）或 group（等价物品组）二选一，count ≥ 1。 */
    public record Need(String item, String group, int count) {
        public boolean isGroup() {
            return group != null;
        }
    }

    public record Stage(String id, String name, List<Need> needs, List<String> facts, List<String> actions, String risks,
                        String say) {
    }

    /** 当前阶段缺的一样东西：要 need.count() 个，手里有 have 个。 */
    public record MissingItem(Need need, int have) {
    }

    /** 算出来的结果：current 为 null 表示全部做完。 */
    public record Assessment(Stage current, List<Stage> done, List<MissingItem> missingItems, List<String> missingFacts) {
        public boolean allDone() {
            return current == null;
        }
    }

    private final Map<String, List<String>> groups;
    private final Map<String, String> groupNames;
    private final Map<String, String> factNames;
    private final List<Stage> stages;

    private Progression(Map<String, List<String>> groups, Map<String, String> groupNames, Map<String, String> factNames,
                        List<Stage> stages) {
        this.groups = Map.copyOf(groups);
        this.groupNames = Map.copyOf(groupNames);
        this.factNames = Map.copyOf(factNames);
        this.stages = List.copyOf(stages);
    }

    /** 没有地图（文件缺失或坏了）：什么都算不出来，describe 会说清。 */
    public static Progression empty() {
        return new Progression(Map.of(), Map.of(), Map.of(), List.of());
    }

    /**
     * 解析 progression.yaml。顶层不是映射、stages 不是列表 → 空地图；
     * 阶段没有 id、needs 引用了不存在的组、needs 一条既没 item 也没 group → 抛 IllegalArgumentException（整份文件不用，调用方记日志）。
     */
    public static Progression parse(String yamlText) {
        Object loaded = new Yaml().load(yamlText == null ? "" : yamlText);
        if (!(loaded instanceof Map<?, ?> root)) {
            return empty();
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, String> groupNames = new LinkedHashMap<>();
        if (root.get("item_groups") instanceof Map<?, ?> gm) {
            for (Map.Entry<?, ?> e : gm.entrySet()) {
                String gid = String.valueOf(e.getKey()).strip();
                Object v = e.getValue();
                if (v instanceof Map<?, ?> m) {
                    groups.put(gid, idList(m.get("items")));
                    if (m.get("name") != null) {
                        groupNames.put(gid, String.valueOf(m.get("name")).strip());
                    }
                } else {
                    groups.put(gid, idList(v));
                }
            }
        }
        Map<String, String> factNames = new LinkedHashMap<>();
        if (root.get("fact_names") instanceof Map<?, ?> fm) {
            fm.forEach((k, v) -> {
                if (k != null && v != null) {
                    factNames.put(String.valueOf(k).strip(), String.valueOf(v).strip());
                }
            });
        }
        List<Stage> stages = new ArrayList<>();
        if (root.get("stages") instanceof List<?> sl) {
            Set<String> ids = new HashSet<>();
            for (Object o : sl) {
                if (!(o instanceof Map<?, ?> sm)) {
                    continue;
                }
                String id = str(sm.get("id"));
                if (id.isEmpty()) {
                    throw new IllegalArgumentException("有一个阶段没有 id");
                }
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("阶段 id 重复：" + id);
                }
                List<Need> needs = new ArrayList<>();
                if (sm.get("needs") instanceof List<?> nl) {
                    for (Object n : nl) {
                        if (!(n instanceof Map<?, ?> nm)) {
                            continue;
                        }
                        String item = str(nm.get("item"));
                        String group = str(nm.get("group"));
                        int count = nm.get("count") == null ? 1 : Integer.parseInt(String.valueOf(nm.get("count")).strip());
                        if (!group.isEmpty()) {
                            if (!groups.containsKey(group)) {
                                throw new IllegalArgumentException("阶段 " + id + " 的 needs 引用了不存在的组 " + group);
                            }
                            needs.add(new Need(null, group, Math.max(1, count)));
                        } else if (!item.isEmpty()) {
                            needs.add(new Need(Knowledge.normalizeId(item), null, Math.max(1, count)));
                        } else {
                            throw new IllegalArgumentException("阶段 " + id + " 的 needs 有一条既没有 item 也没有 group");
                        }
                    }
                }
                stages.add(new Stage(id, str(sm.get("name")).isEmpty() ? id : str(sm.get("name")), List.copyOf(needs),
                        strList(sm.get("facts")), strList(sm.get("actions")), str(sm.get("risks")), str(sm.get("say"))));
            }
        }
        return new Progression(groups, groupNames, factNames, stages);
    }

    public boolean isEmpty() {
        return stages.isEmpty();
    }

    public List<Stage> stages() {
        return stages;
    }

    public Optional<Stage> stage(String id) {
        return stages.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public Map<String, List<String>> groups() {
        return groups;
    }

    /** 背包里这条目标对应的数量：单个物品直接取，组则把组内所有物品加起来。 */
    public int count(Map<String, Integer> inventory, Need need) {
        if (inventory == null || inventory.isEmpty()) {
            return 0;
        }
        if (need.isGroup()) {
            int sum = 0;
            for (String id : groups.getOrDefault(need.group(), List.of())) {
                sum += Math.max(0, inventory.getOrDefault(id, 0));
            }
            return sum;
        }
        return Math.max(0, inventory.getOrDefault(need.item(), 0));
    }

    /** 这个阶段现在算不算做完：needs 全满足且 facts 全有。 */
    public boolean satisfied(Stage s, Map<String, Integer> inventory, Set<String> facts) {
        for (Need n : s.needs()) {
            if (count(inventory, n) < n.count()) {
                return false;
            }
        }
        for (String f : s.facts()) {
            if (facts == null || !facts.contains(f)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 算当前阶段和缺什么。
     *
     * @param inventory 伙伴背包（物品 id → 数量；id 可以不带 minecraft: 前缀）
     * @param facts     已经有的世界事实 id
     * @param doneIds   progress 表里记过的阶段 id（任务单 17 之前传空集合）
     */
    public Assessment assess(Map<String, Integer> inventory, Set<String> facts, Set<String> doneIds) {
        Map<String, Integer> inv = normalize(inventory);
        Set<String> f = facts == null ? Set.of() : facts;
        Set<String> recorded = doneIds == null ? Set.of() : doneIds;
        int n = stages.size();
        boolean[] self = new boolean[n];
        for (int i = 0; i < n; i++) {
            self[i] = recorded.contains(stages.get(i).id()) || satisfied(stages.get(i), inv, f);
        }
        // 后面的做完了前面的也算做完：从后往前传
        boolean[] done = new boolean[n];
        boolean later = false;
        for (int i = n - 1; i >= 0; i--) {
            done[i] = self[i] || later;
            later = done[i];
        }
        List<Stage> doneStages = new ArrayList<>();
        Stage current = null;
        for (int i = 0; i < n; i++) {
            if (done[i]) {
                doneStages.add(stages.get(i));
            } else {
                current = stages.get(i);
                break;
            }
        }
        List<MissingItem> missingItems = new ArrayList<>();
        List<String> missingFacts = new ArrayList<>();
        if (current != null) {
            for (Need need : current.needs()) {
                int have = count(inv, need);
                if (have < need.count()) {
                    missingItems.add(new MissingItem(need, have));
                }
            }
            for (String fact : current.facts()) {
                if (!f.contains(fact)) {
                    missingFacts.add(fact);
                }
            }
        }
        return new Assessment(current, List.copyOf(doneStages), List.copyOf(missingItems), List.copyOf(missingFacts));
    }

    /** 给主人看的一句话：现在在哪个阶段、缺什么、建议先做什么。算出来的事实，不经过模型。 */
    public String describe(Assessment a, ItemNames names) {
        if (isEmpty()) {
            return "我还没有通关地图（knowledge/progression.yaml 缺了或者坏了），说不清走到哪了。";
        }
        if (a.allDone()) {
            return "通关路上的 " + stages.size() + " 个阶段都做完了，龙打过了。";
        }
        Stage s = a.current();
        int index = stages.indexOf(s) + 1;
        StringBuilder sb = new StringBuilder();
        sb.append("现在在「").append(s.name()).append("」阶段（").append(stages.size()).append(" 个阶段里的第 ").append(index)
                .append(" 个）");
        List<String> lack = new ArrayList<>();
        for (MissingItem m : a.missingItems()) {
            lack.add(label(m.need(), names) + " " + m.have() + "/" + m.need().count());
        }
        if (!lack.isEmpty()) {
            sb.append("，缺：").append(String.join("、", lack));
        }
        if (!a.missingFacts().isEmpty()) {
            List<String> fl = new ArrayList<>();
            for (String fact : a.missingFacts()) {
                fl.add(factName(fact));
            }
            sb.append("，还没做到：").append(String.join("、", fl));
        }
        sb.append("。");
        if (!s.say().isEmpty()) {
            sb.append(s.say());
        }
        return sb.toString();
    }

    /** 一条目标的中文叫法：组用组名，单个物品查 items_zh。 */
    public String label(Need need, ItemNames names) {
        if (need.isGroup()) {
            return groupNames.getOrDefault(need.group(), need.group());
        }
        return names == null ? need.item() : names.name(need.item());
    }

    public String factName(String fact) {
        return factNames.getOrDefault(fact, fact);
    }

    private static Map<String, Integer> normalize(Map<String, Integer> inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        inventory.forEach((k, v) -> {
            if (k != null && v != null) {
                out.merge(Knowledge.normalizeId(k), v, Integer::sum);
            }
        });
        return out;
    }

    private static List<String> idList(Object o) {
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : l) {
            String id = Knowledge.normalizeId(item == null ? "" : item.toString());
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : l) {
            String s = str(item);
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }
}
