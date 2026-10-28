package dev.blockmate.companion;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 白名单（任务单 12）：后台在 hello 里下发的"能挖什么、能放什么"。
 * 模组里所有会破坏或放置方块的代码只能问这里；没收到清单之前什么都不许。纯逻辑，可单测。
 */
public final class Rules {

    public static final Rules EMPTY = new Rules(Set.of(), Set.of(), Map.of(), Set.of(), Set.of(), false,
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "defend");

    private final Set<String> mineable;
    private final Set<String> placeable;
    private final Map<String, String> drops;
    private final Set<String> avoidFood;
    private final Set<String> needsPickaxe;
    private final boolean received;
    // 任务单 19：怪物名单和战斗开关
    private final Set<String> hostile;
    private final Set<String> fleeOnly;
    private final Set<String> never;
    private final Set<String> hostileAtNight;
    private final Set<String> hostileIfAttacked;
    private final String combatMode;

    private Rules(Set<String> mineable, Set<String> placeable, Map<String, String> drops, Set<String> avoidFood,
                  Set<String> needsPickaxe, boolean received, Set<String> hostile, Set<String> fleeOnly, Set<String> never,
                  Set<String> hostileAtNight, Set<String> hostileIfAttacked, String combatMode) {
        this.mineable = mineable;
        this.placeable = placeable;
        this.drops = drops;
        this.avoidFood = avoidFood;
        this.needsPickaxe = needsPickaxe;
        this.received = received;
        this.hostile = hostile;
        this.fleeOnly = fleeOnly;
        this.never = never;
        this.hostileAtNight = hostileAtNight;
        this.hostileIfAttacked = hostileIfAttacked;
        this.combatMode = combatMode;
    }

    /** 任务单 19：加上怪物名单和战斗开关（off / defend / protect，别的当 defend）。 */
    public Rules withCombat(Collection<String> hostile, Collection<String> fleeOnly, Collection<String> never,
                            Collection<String> hostileAtNight, Collection<String> hostileIfAttacked, String combatMode) {
        String mode = combatMode == null ? "" : combatMode.strip().toLowerCase();
        if (!mode.equals("off") && !mode.equals("protect")) {
            mode = "defend";
        }
        return new Rules(mineable, placeable, drops, avoidFood, needsPickaxe, received, normalizeAll(hostile), normalizeAll(fleeOnly),
                normalizeAll(never), normalizeAll(hostileAtNight), normalizeAll(hostileIfAttacked), mode);
    }

    public Set<String> hostile() {
        return hostile;
    }

    public Set<String> fleeOnly() {
        return fleeOnly;
    }

    public Set<String> never() {
        return never;
    }

    public Set<String> hostileAtNight() {
        return hostileAtNight;
    }

    public Set<String> hostileIfAttacked() {
        return hostileIfAttacked;
    }

    public String combatMode() {
        return combatMode;
    }

    /** 徒手挖了不掉东西的方块（石头、矿、熔炉）：没镐别去挖。 */
    public boolean needsPickaxe(String blockId) {
        return needsPickaxe.contains(normalize(blockId));
    }

    public static Rules of(Collection<String> mineable, Collection<String> placeable) {
        return of(mineable, placeable, null, null);
    }

    public static Rules of(Collection<String> mineable, Collection<String> placeable, Map<String, String> drops) {
        return of(mineable, placeable, drops, null);
    }

    /** 不该吃的食物（任务单 15），只有没别的可吃时才碰。 */
    public boolean shouldAvoidFood(String itemId) {
        return avoidFood.contains(normalize(itemId));
    }

    public Set<String> avoidFood() {
        return avoidFood;
    }

    /**
     * @param drops     方块 → 挖掉后得到的物品（任务单 13），可为 null
     * @param avoidFood 不该吃的食物（任务单 15），可为 null
     */
    public static Rules of(Collection<String> mineable, Collection<String> placeable, Map<String, String> drops, Collection<String> avoidFood) {
        return of(mineable, placeable, drops, avoidFood, null);
    }

    public static Rules of(Collection<String> mineable, Collection<String> placeable, Map<String, String> drops, Collection<String> avoidFood,
                           Collection<String> needsPickaxe) {
        Map<String, String> d = new LinkedHashMap<>();
        if (drops != null) {
            drops.forEach((k, v) -> {
                String nk = normalize(k);
                String nv = normalize(v);
                if (!nk.isEmpty() && !nv.isEmpty()) {
                    d.put(nk, nv);
                }
            });
        }
        return new Rules(normalizeAll(mineable), normalizeAll(placeable), Map.copyOf(d), normalizeAll(avoidFood), normalizeAll(needsPickaxe), true,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "defend");
    }

    /** 这种方块挖掉后背包里多的是什么；没写的和方块同名。 */
    public String dropOf(String blockId) {
        String b = normalize(blockId);
        return drops.getOrDefault(b, b);
    }

    /** 收到过后台的清单没有（没收到 = 全不许，而且说明 hello 不对）。 */
    public boolean received() {
        return received;
    }

    public boolean canMine(String blockId) {
        return mineable.contains(normalize(blockId));
    }

    public boolean canPlace(String itemId) {
        return placeable.contains(normalize(itemId));
    }

    public int mineableCount() {
        return mineable.size();
    }

    /** 全部可挖的方块 id（挖矿时整张清单放行，才能挖穿泥土、石头够到矿）。 */
    public Set<String> mineableIds() {
        return mineable;
    }

    public int placeableCount() {
        return placeable.size();
    }

    /** 统一成 minecraft:xxx 小写。 */
    static String normalize(String id) {
        String v = id == null ? "" : id.strip().toLowerCase();
        if (v.isEmpty()) {
            return v;
        }
        return v.contains(":") ? v : "minecraft:" + v;
    }

    private static Set<String> normalizeAll(Collection<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                String n = normalize(id);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        }
        return Set.copyOf(out);
    }

    @Override
    public String toString() {
        return "Rules{mineable=" + mineable.size() + ", placeable=" + placeable.size() + ", received=" + received + "}";
    }
}
