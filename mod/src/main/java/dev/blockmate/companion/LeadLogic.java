package dev.blockmate.companion;

/**
 * 带路的纯逻辑（任务单 21，guide-3 §3.1）：主人落后多远停下等、靠近多近再走，前方有没有岩浆或断崖。
 * 不碰游戏类，方便测试；方块怎么读由 {@link Probe} 提供。
 */
public final class LeadLogic {

    /** 主世界：落后 32 格停下等，回到 16 格内再走；下界 / 末地减半。 */
    public static final double FAR_OVERWORLD = 32.0;
    public static final double NEAR_OVERWORLD = 16.0;
    public static final double FAR_OTHER = 16.0;
    public static final double NEAR_OTHER = 8.0;
    /** 前方看 4 格；脚下空 4 格以上算断崖；断崖只看前 3 格（远了坡会误报）。 */
    public static final int LOOKAHEAD = 4;
    public static final int CLIFF_LOOKAHEAD = 3;
    public static final int CLIFF_DROP = 4;
    /** 危险停下后等主人说"继续"最多 2 分钟；主人 30 秒看不见算跟丢。 */
    public static final int DANGER_WAIT_TICKS = 2400;
    public static final int OWNER_LOST_TICKS = 600;
    public static final int DANGER_CHECK_EVERY_TICKS = 10;

    public static final String LAVA = "lava";
    public static final String CLIFF = "cliff";

    /** 读方块：是不是岩浆、能不能站（有碰撞箱）。 */
    public interface Probe {
        boolean isLava(int x, int y, int z);

        boolean isSolid(int x, int y, int z);
    }

    private LeadLogic() {
    }

    public static double farLimit(String dim) {
        return isOverworld(dim) ? FAR_OVERWORLD : FAR_OTHER;
    }

    public static double nearLimit(String dim) {
        return isOverworld(dim) ? NEAR_OVERWORLD : NEAR_OTHER;
    }

    private static boolean isOverworld(String dim) {
        return dim == null || "minecraft:overworld".equals(dim);
    }

    /**
     * 前方有没有危险。(x, y, z) 是脚所在的方块，(dx, dz) 是前进方向（不用归一化）。
     * 返回 "lava" / "cliff"，没有返回 null。岩浆看脚平面和脚下一格；断崖看脚下连续空 4 格以上。
     */
    public static String dangerAhead(Probe p, int x, int y, int z, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.01) {
            return null;
        }
        double ux = dx / len;
        double uz = dz / len;
        for (int i = 1; i <= LOOKAHEAD; i++) {
            int ax = (int) Math.round(x + ux * i);
            int az = (int) Math.round(z + uz * i);
            if (p.isLava(ax, y, az) || p.isLava(ax, y - 1, az)) {
                return LAVA;
            }
            if (i <= CLIFF_LOOKAHEAD && p.isSolid(ax, y, az) == false && dropBelow(p, ax, y, az) > CLIFF_DROP) {
                return CLIFF;
            }
        }
        return null;
    }

    /** 从脚平面往下数，连续几格不能站（最多数到 CLIFF_DROP + 1）。 */
    static int dropBelow(Probe p, int x, int y, int z) {
        int n = 0;
        for (int d = 1; d <= CLIFF_DROP + 1; d++) {
            if (p.isSolid(x, y - d, z)) {
                return n;
            }
            n++;
        }
        return n;
    }
}
