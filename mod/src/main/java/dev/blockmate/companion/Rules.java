package dev.blockmate.companion;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 白名单（任务单 12）：后台在 hello 里下发的"能挖什么、能放什么"。
 * 模组里所有会破坏或放置方块的代码只能问这里；没收到清单之前什么都不许。纯逻辑，可单测。
 */
public final class Rules {

    public static final Rules EMPTY = new Rules(Set.of(), Set.of(), false);

    private final Set<String> mineable;
    private final Set<String> placeable;
    private final boolean received;

    private Rules(Set<String> mineable, Set<String> placeable, boolean received) {
        this.mineable = mineable;
        this.placeable = placeable;
        this.received = received;
    }

    public static Rules of(Collection<String> mineable, Collection<String> placeable) {
        return new Rules(normalizeAll(mineable), normalizeAll(placeable), true);
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
