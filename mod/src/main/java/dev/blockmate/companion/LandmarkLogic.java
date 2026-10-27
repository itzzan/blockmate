package dev.blockmate.companion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 地标扫描的纯逻辑（任务单 17，guide-3 §1.2 第 2b 条）：一遍扫描的统计怎么变成"看见了什么"，以及同种地标 100 格内只报一次。
 * 不碰游戏类，方便测试；扫方块和找村民在 {@link LandmarkScanner}。
 */
public final class LandmarkLogic {

    /** 水平半径 24 格、上下 8 格（手册定 24 格，上下压到 8 层是怕扫太多卡客户端）。 */
    public static final int RADIUS = 24;
    public static final int VERTICAL = 8;
    /** 岩浆只看 8 格内，≥ 20 块才算危险。 */
    public static final int LAVA_RADIUS = 8;
    public static final int LAVA_MIN = 20;
    /** 下界里下界砖 ≥ 20 块才算要塞（避免几块零散的）。 */
    public static final int FORTRESS_MIN_BRICKS = 20;
    /** 没钟的村庄：24 格内 ≥ 2 个村民也算（主人定）。 */
    public static final int VILLAGER_MIN = 2;
    /** 同种地标 100 格内只报一次；危险 32 格。 */
    public static final double REPORT_RADIUS = 100;
    public static final double DANGER_REPORT_RADIUS = 32;
    /** 扫完一遍（49 tick）歇这么多 tick，合起来约每 5 秒一遍。 */
    public static final int PAUSE_TICKS = 50;

    public static final String BELL = "bell";
    public static final String NETHER_PORTAL = "nether_portal";
    public static final String NETHER_BRICKS = "nether_bricks";
    public static final String END_PORTAL_FRAME = "end_portal_frame";
    public static final String MOSSY_STONE_BRICKS = "mossy_stone_bricks";
    public static final String STONE_BRICK_STAIRS = "stone_brick_stairs";
    public static final String LAVA_NEAR = "lava_near";

    public static final String DIM_OVERWORLD = "minecraft:overworld";
    public static final String DIM_NETHER = "minecraft:the_nether";
    public static final String DIM_END = "minecraft:the_end";

    /** 一遍扫描的统计：每种关心的方块有几个、第一次见到的位置。 */
    public static final class Tally {
        private final Map<String, Integer> counts = new HashMap<>();
        private final Map<String, int[]> first = new HashMap<>();

        public void hit(String key, int x, int y, int z) {
            counts.merge(key, 1, Integer::sum);
            first.putIfAbsent(key, new int[]{x, y, z});
        }

        public int count(String key) {
            return counts.getOrDefault(key, 0);
        }

        public int[] first(String key) {
            return first.get(key);
        }
    }

    /** 看见的一样东西：kind 是后台认的种类（village / portal_overworld / … / lava），danger 决定发 landmark 还是 danger 事件。 */
    public record Found(String kind, boolean danger, double x, double y, double z) {
    }

    private record Reported(String kind, String dim, double x, double z) {
    }

    private final List<Reported> reported = new ArrayList<>();

    /** 统计 → 这一遍看见了哪些地标。村庄：有钟，或村民 ≥ 2；传送门按维度分；要塞只在下界认；遗迹要塞要苔石砖和石砖楼梯同时有。 */
    public static List<Found> classify(Tally t, String dim, int villagers, double[] villagerPos) {
        List<Found> out = new ArrayList<>();
        boolean nether = DIM_NETHER.equals(dim);
        boolean end = DIM_END.equals(dim);
        boolean overworld = !nether && !end;
        if (t.count(BELL) > 0) {
            out.add(at("village", false, t.first(BELL)));
        } else if (villagers >= VILLAGER_MIN && villagerPos != null) {
            out.add(new Found("village", false, villagerPos[0], villagerPos[1], villagerPos[2]));
        }
        if (!end && t.count(NETHER_PORTAL) > 0) {
            out.add(at(nether ? "portal_nether" : "portal_overworld", false, t.first(NETHER_PORTAL)));
        }
        if (nether && t.count(NETHER_BRICKS) >= FORTRESS_MIN_BRICKS) {
            out.add(at("fortress", false, t.first(NETHER_BRICKS)));
        }
        if (t.count(END_PORTAL_FRAME) > 0) {
            out.add(at("end_portal", false, t.first(END_PORTAL_FRAME)));
        }
        if (overworld && t.count(MOSSY_STONE_BRICKS) > 0 && t.count(STONE_BRICK_STAIRS) > 0) {
            out.add(at("stronghold", false, t.first(MOSSY_STONE_BRICKS)));
        }
        if (t.count(LAVA_NEAR) >= LAVA_MIN) {
            out.add(at("lava", true, t.first(LAVA_NEAR)));
        }
        return out;
    }

    private static Found at(String kind, boolean danger, int[] p) {
        return new Found(kind, danger, p[0] + 0.5, p[1], p[2] + 0.5);
    }

    /** 同种地标 100 格内（危险 32 格内）只报一次；要报就记下来并返回 true。 */
    public boolean shouldReport(Found f, String dim) {
        double r = f.danger() ? DANGER_REPORT_RADIUS : REPORT_RADIUS;
        for (Reported p : reported) {
            if (p.kind().equals(f.kind()) && Objects.equals(p.dim(), dim) && Math.hypot(p.x() - f.x(), p.z() - f.z()) <= r) {
                return false;
            }
        }
        if (reported.size() >= 500) {
            reported.remove(0);
        }
        reported.add(new Reported(f.kind(), dim, f.x(), f.z()));
        return true;
    }

    public int reportedCount() {
        return reported.size();
    }
}
