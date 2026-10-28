package dev.blockmate.companion;

/**
 * 打龙的纯逻辑（任务单 23，主人定的细则）：每 tick 按优先级决定做哪一样；笼子怎么判；躲到柱子背面站哪。
 * 不碰游戏类；感知和动手在 {@link Combat}。
 */
public final class DragonLogic {

    public enum Action { DODGE_BREATH, RETREAT, MELEE, SHOOT_CRYSTAL, GUARD_OWNER, GUARD_PORTAL, TIRED }

    /** 龙息 3 格内躲；龙 6 格内砍；水晶 6–40 格射；血 ≤ 8 撤，≥ 14 再上；主人不在且 60 秒没造成伤害就守传送门；15 分钟还没打死算打不动。 */
    public static final double BREATH_RADIUS = 3.0;
    public static final double DRAGON_MELEE = 6.0;
    public static final double CRYSTAL_MIN = 6.0;
    public static final double CRYSTAL_MAX = 40.0;
    public static final float RETREAT_HEALTH = 8f;
    public static final float RESUME_HEALTH = 14f;
    public static final int NO_DAMAGE_GUARD_TICKS = 1200;
    public static final int TIRED_TICKS = 18000;
    public static final double GUARD_OWNER_DISTANCE = 6.0;
    public static final double PILLAR_OFFSET = 3.0;

    public record Situation(boolean breathNear, float health, boolean retreating, double dragonDist, boolean hasBowArrow,
                            boolean crystalVisible, boolean ownerPresent, int ticksSinceDamageDealt, int fightTicks, boolean tiredSaid) {
    }

    private DragonLogic() {
    }

    public static Action decide(Situation s) {
        if (s.breathNear()) {
            return Action.DODGE_BREATH;
        }
        if (s.fightTicks() > TIRED_TICKS && !s.tiredSaid()) {
            return Action.TIRED;
        }
        if (s.health() <= RETREAT_HEALTH || (s.retreating() && s.health() < RESUME_HEALTH)) {
            return Action.RETREAT;
        }
        if (!Double.isNaN(s.dragonDist()) && s.dragonDist() <= DRAGON_MELEE) {
            return Action.MELEE;
        }
        if (!s.ownerPresent() && s.ticksSinceDamageDealt > NO_DAMAGE_GUARD_TICKS) {
            return Action.GUARD_PORTAL;
        }
        if (s.hasBowArrow() && s.crystalVisible() && !s.tiredSaid()) {
            return Action.SHOOT_CRYSTAL;
        }
        return s.ownerPresent() ? Action.GUARD_OWNER : Action.GUARD_PORTAL;
    }

    /** 读方块：是不是铁栏杆。 */
    public interface BarsProbe {
        boolean isIronBars(int x, int y, int z);
    }

    /** 水晶周围（水平 1 格、上面 1–2 格）有铁栏杆就是笼子里的，箭射不到。 */
    public static boolean caged(BarsProbe p, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (p.isIronBars(x + dx, y, z + dz) || p.isIronBars(x + dx, y + 1, z + dz) || p.isIronBars(x + dx, y + 2, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 柱子背面的站点：从龙到柱子的方向再往外 3 格。 */
    public static double[] behindPillar(double pillarX, double pillarZ, double dragonX, double dragonZ) {
        double dx = pillarX - dragonX;
        double dz = pillarZ - dragonZ;
        double len = Math.hypot(dx, dz);
        if (len < 0.01) {
            return new double[]{pillarX + PILLAR_OFFSET, pillarZ};
        }
        return new double[]{pillarX + dx / len * PILLAR_OFFSET, pillarZ + dz / len * PILLAR_OFFSET};
    }

    /** 眼的方向：MC yaw（0 = 南 +z，90 = 西 -x）。 */
    public static double yawOf(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** 10 tick 水平没飞出 1 格 = 眼就在要塞上头，不飞。 */
    public static boolean eyeNear(double dx, double dz) {
        return Math.hypot(dx, dz) < 1.0;
    }
}
