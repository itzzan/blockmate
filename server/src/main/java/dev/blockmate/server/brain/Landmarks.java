package dev.blockmate.server.brain;

import dev.blockmate.protocol.Pos;
import dev.blockmate.server.memory.StoredWorldFact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 地标的常识（任务单 17）：种类的中文名、主人话里的词对应哪一种、方向和距离怎么说。
 * 纯函数，hub 和提示词都用它；不碰数据库。
 */
public final class Landmarks {

    public static final String VILLAGE = "village";
    public static final String PORTAL_OVERWORLD = "portal_overworld";
    public static final String PORTAL_NETHER = "portal_nether";
    public static final String FORTRESS = "fortress";
    public static final String STRONGHOLD = "stronghold";
    public static final String END_PORTAL = "end_portal";
    public static final String MINE_SITE = "mine_site";
    public static final String DANGER = "danger";

    public static final String DIM_NETHER = "minecraft:the_nether";
    public static final String DIM_END = "minecraft:the_end";

    private static final String[] DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    private Landmarks() {
    }

    public static String kindName(String kind) {
        return switch (kind == null ? "" : kind) {
            case VILLAGE -> "村庄";
            case PORTAL_OVERWORLD -> "主世界的传送门";
            case PORTAL_NETHER -> "下界的传送门";
            case FORTRESS -> "下界要塞";
            case STRONGHOLD -> "要塞";
            case END_PORTAL -> "末地传送门";
            case MINE_SITE -> "矿点";
            case DANGER -> "岩浆";
            default -> kind == null ? "" : kind;
        };
    }

    /**
     * 主人说"记住这里是 xx"或"去 xx"时，xx 是哪一种地标；认不出返回 null（调用方当普通地点名）。
     * 传送门和要塞按当前维度分主世界 / 下界。
     */
    public static String kindOf(String word, String dim) {
        String w = word == null ? "" : word.strip().toLowerCase();
        boolean nether = DIM_NETHER.equals(dim);
        return switch (w) {
            case "村庄", "村子", "村", "村庄里", VILLAGE -> VILLAGE;
            case "矿洞", "矿", "矿点", "矿区", "矿井", "洞口", MINE_SITE -> MINE_SITE;
            case "传送门", "地狱门", "下界门", "门", "传送门口" -> nether ? PORTAL_NETHER : PORTAL_OVERWORLD;
            case PORTAL_OVERWORLD, "主世界传送门", "主世界的传送门" -> PORTAL_OVERWORLD;
            case PORTAL_NETHER, "下界传送门", "下界的传送门" -> PORTAL_NETHER;
            case "要塞" -> nether ? FORTRESS : STRONGHOLD;
            case "下界要塞", "地狱要塞", FORTRESS -> FORTRESS;
            case STRONGHOLD, "末地要塞", "主世界要塞" -> STRONGHOLD;
            case "末地传送门", "末地门", "末地门口", "末地传送门口", END_PORTAL -> END_PORTAL;
            case "岩浆", "危险", DANGER -> DANGER;
            default -> null;
        };
    }

    /** 从 (dx, dz) 看方向：北是 -z，东是 +x，八个方位。 */
    public static String direction(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = 北，90 = 东，±180 = 南，-90 = 西
        int sector = (int) Math.floorMod(Math.round(angle / 45.0), 8L);
        return DIRECTIONS[sector];
    }

    /** "村庄（西北 60 格）"；不在同一维度写"（下界）"；主人起过名的带上名字。 */
    public static String describe(StoredWorldFact f, Pos from, String fromDim) {
        String name = kindName(f.kind());
        if (f.name() != null && !f.name().isBlank() && !f.name().equals(name)) {
            name = f.name() + "（" + name + "）";
        }
        if (fromDim != null && f.dim() != null && !fromDim.equals(f.dim())) {
            return name + "（" + SnapshotSummary.dimName(f.dim()) + "）";
        }
        if (from == null) {
            return name;
        }
        double dx = f.x() - from.x();
        double dz = f.z() - from.z();
        long dist = Math.round(Math.hypot(dx, dz));
        if (dist < 4) {
            return name + "（就在这儿）";
        }
        return name + "（" + direction(dx, dz) + " " + dist + " 格）";
    }

    /** 最近的 max 条：同一维度的按距离排，别的维度排后面；from 为 null 时按记录时间倒序。 */
    public static List<String> nearest(List<StoredWorldFact> facts, Pos from, String dim, int max) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<StoredWorldFact> sorted = new ArrayList<>(facts);
        if (from != null) {
            sorted.sort(Comparator.comparingDouble(f -> sameDim(f, dim) ? Math.hypot(f.x() - from.x(), f.z() - from.z())
                    : Double.MAX_VALUE));
        } else {
            sorted.sort(Comparator.comparingLong(StoredWorldFact::ts).reversed());
        }
        List<String> out = new ArrayList<>();
        for (StoredWorldFact f : sorted) {
            if (out.size() >= max) {
                break;
            }
            out.add(describe(f, from, dim));
        }
        return out;
    }

    private static boolean sameDim(StoredWorldFact f, String dim) {
        return dim == null || f.dim() == null || dim.equals(f.dim());
    }
}
