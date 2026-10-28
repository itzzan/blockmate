package dev.blockmate.companion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 搭传送门的纯逻辑（任务单 22）：图纸的相对坐标怎么落到世界里、脚边哪有平地、十几块按什么顺序放、每块靠哪块放。
 * 不碰游戏类；方块怎么读由 {@link Probe} 提供。放和点火在 Executor。
 */
public final class PortalLogic {

    public record Cell(int x, int y, int z) {
        public Cell plus(int dx, int dy, int dz) {
            return new Cell(x + dx, y + dy, z + dz);
        }
    }

    /** 门框所在的竖直平面：X = 沿 x 轴（图纸原样），Z = 转 90 度（图纸的 dx 变成 dz）。 */
    public enum Axis { X, Z }

    public record Site(Cell origin, Axis axis) {
    }

    /** 放一块：target 放在哪，support 靠哪块，face 从 support 指向 target 的方向（dx, dy, dz 里只有一个非零）。 */
    public record Placement(Cell target, Cell support, int dx, int dy, int dz) {
    }

    public interface Probe {
        /** 空气或可以被盖掉的东西（草、雪）。 */
        boolean isFree(int x, int y, int z);

        /** 上表面结实，能把方块放在它上面。 */
        boolean isSolidTop(int x, int y, int z);
    }

    /** 脚边找平地的范围（手册：3 格内）。 */
    public static final int SEARCH_RADIUS = 3;
    /** 站在门框前面几格放（4 宽 5 高从 2 格外都够得着）。 */
    public static final int STAND_BACK = 2;

    private PortalLogic() {
    }

    public static Cell abs(Cell origin, List<Integer> rel, Axis axis) {
        int dx = rel.get(0);
        int dy = rel.get(1);
        int dz = rel.get(2);
        return axis == Axis.X ? origin.plus(dx, dy, dz) : origin.plus(dz, dy, dx);
    }

    public static List<Cell> absAll(Cell origin, List<List<Integer>> rels, Axis axis) {
        List<Cell> out = new ArrayList<>();
        for (List<Integer> r : rels) {
            out.add(abs(origin, r, axis));
        }
        return out;
    }

    /**
     * 脚边 3 格内找一块能搭门的地方：门框、角、门里都得是空的，贴地那一行脚下得结实，而且不能把自己围在门框里。
     * 先按图纸方向找，找不到转 90 度；离脚越近越先试。
     */
    public static Optional<Site> findSite(Probe p, Cell feet, List<List<Integer>> frame, List<List<Integer>> corners,
                                          List<List<Integer>> inside) {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort((a, b) -> Integer.compare(Math.abs(a[0]) + Math.abs(a[1]), Math.abs(b[0]) + Math.abs(b[1])));
        for (Axis axis : Axis.values()) {
            for (int[] o : offsets) {
                Cell origin = feet.plus(o[0], 0, o[1]);
                if (fits(p, feet, origin, axis, frame, corners, inside)) {
                    return Optional.of(new Site(origin, axis));
                }
            }
        }
        return Optional.empty();
    }

    static boolean fits(Probe p, Cell feet, Cell origin, Axis axis, List<List<Integer>> frame, List<List<Integer>> corners,
                        List<List<Integer>> inside) {
        List<Cell> all = new ArrayList<>();
        all.addAll(absAll(origin, frame, axis));
        all.addAll(absAll(origin, corners, axis));
        all.addAll(absAll(origin, inside, axis));
        Cell head = feet.plus(0, 1, 0);
        for (Cell c : all) {
            if (c.equals(feet) || c.equals(head)) {
                return false;
            }
            if (!p.isFree(c.x(), c.y(), c.z())) {
                return false;
            }
        }
        for (Cell c : all) {
            if (c.y() == origin.y() && !p.isSolidTop(c.x(), c.y() - 1, c.z())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 放的顺序和每块靠谁：贴地那行靠地面；上面的靠下面那块；顶上那排靠角。
     * 返回的顺序保证每一块放的时候 support 已经在了；放不全（图纸怪）就少返回几块。
     */
    public static List<Placement> order(List<Cell> cells, Cell origin, Axis axis) {
        List<Cell> remaining = new ArrayList<>(cells);
        remaining.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : axis == Axis.X ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        Set<Cell> placed = new HashSet<>();
        List<Placement> out = new ArrayList<>();
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            for (int i = 0; i < remaining.size(); i++) {
                Cell c = remaining.get(i);
                Placement pl = supportFor(c, origin, axis, placed);
                if (pl != null) {
                    out.add(pl);
                    placed.add(c);
                    remaining.remove(i);
                    progress = true;
                    break;
                }
            }
        }
        return out;
    }

    private static Placement supportFor(Cell c, Cell origin, Axis axis, Set<Cell> placed) {
        Cell below = c.plus(0, -1, 0);
        if (c.y() == origin.y() || placed.contains(below)) {
            return new Placement(c, below, 0, 1, 0);
        }
        Cell left = axis == Axis.X ? c.plus(-1, 0, 0) : c.plus(0, 0, -1);
        Cell right = axis == Axis.X ? c.plus(1, 0, 0) : c.plus(0, 0, 1);
        if (placed.contains(left)) {
            return axis == Axis.X ? new Placement(c, left, 1, 0, 0) : new Placement(c, left, 0, 0, 1);
        }
        if (placed.contains(right)) {
            return axis == Axis.X ? new Placement(c, right, -1, 0, 0) : new Placement(c, right, 0, 0, -1);
        }
        return null;
    }

    /** 站哪放：门框中间正前方 2 格的两个候选（前后各一个），调用方挑空的那个。 */
    public static List<Cell> standingSpots(Site site) {
        Cell o = site.origin();
        if (site.axis() == Axis.X) {
            return List.of(o.plus(1, 0, -STAND_BACK), o.plus(1, 0, STAND_BACK));
        }
        return List.of(o.plus(-STAND_BACK, 0, 1), o.plus(STAND_BACK, 0, 1));
    }

    /** 够不够得着：从眼睛到方块中心不超过 4.5 格。 */
    public static boolean inReach(double eyeX, double eyeY, double eyeZ, Cell c) {
        double dx = c.x() + 0.5 - eyeX;
        double dy = c.y() + 0.5 - eyeY;
        double dz = c.z() + 0.5 - eyeZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= 4.5;
    }
}
