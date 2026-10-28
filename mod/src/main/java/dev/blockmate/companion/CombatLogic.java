package dev.blockmate.companion;

import java.util.Set;

/**
 * 自保的纯逻辑（任务单 19，guide-3 §2.1）：名单怎么判、什么时候算被攻击、每 tick 该做哪一样。
 * 全是写死的规则，不经过模型；不碰游戏类，方便测试。感知和动手在 {@link Combat}。
 */
public final class CombatLogic {

    /** 一个生物对我们来说是什么。 */
    public enum Kind { HOSTILE, FLEE, NEVER, IGNORE }

    /** 这一 tick 该做什么（按优先级从高到低判）。任务单 20 加 GUARD（顶在主人前面）和 SHOOT（用弓）。 */
    public enum Action { NONE, FLEE, GIVE_UP, EAT_RUN, SHIELD_BACK, GUARD, ATTACK, SHOOT, APPROACH }

    /** 任务单 20：保护主人——以主人为中心 8 格找怪；"打那只"16 格内找、目标跑出 24 格算打完、60 秒超时；主人血 ≤ 8 顶在前面；6 格外用弓。 */
    public static final double PROTECT_RADIUS = 8.0;
    public static final double HUNT_RADIUS = 16.0;
    public static final double HUNT_LOSE_RADIUS = 24.0;
    public static final int HUNT_TIMEOUT_TICKS = 1200;
    public static final int HUNT_SEARCH_GRACE_TICKS = 40;
    public static final float OWNER_LOW_HEALTH = 8f;
    public static final double BOW_MIN_DISTANCE = 6.0;
    public static final int BOW_CHARGE_TICKS = 20;
    /** 主人离那只怪 3 格内又在挥手 = 主人正在打它，不抢。 */
    public static final double OWNER_FIGHTING_DISTANCE = 3.0;

    /** 8 格内的怪才算威胁；3 格内（或先动手）才开打；苦力怕 3 格内就躲、躲到 6 格外。 */
    public static final double ENGAGE_RADIUS = 8.0;
    public static final double TRIGGER_RADIUS = 3.0;
    public static final double FLEE_TRIGGER = 3.0;
    public static final double FLEE_DISTANCE = 6.0;
    /** 挥得着的距离。 */
    public static final double REACH = 3.2;
    /** 一场架最多 30 秒；8 格内 3 秒没怪算打完；举盾后退 5 秒内不再举。 */
    public static final int MAX_FIGHT_TICKS = 600;
    public static final int CALM_TICKS = 60;
    public static final int SHIELD_COOLDOWN_TICKS = 100;
    public static final int SHIELD_BACK_TICKS = 40;
    /** 血 ≤ 8 举盾后退；≤ 6 吃一口往主人跑；10 秒内掉 8 血算打不过。 */
    public static final float SHIELD_HEALTH = 8f;
    public static final float EAT_HEALTH = 6f;
    public static final float OVERWHELMED_DAMAGE_10S = 8f;
    /** 攻击冷却满到这个比例才挥（满力一击）。 */
    public static final float SWING_STRENGTH = 0.9f;

    private CombatLogic() {
    }

    /**
     * 名单判定。never 最大，其次 flee_only，再是 hostile；at_night / if_attacked 只对不在前三段里的生效。
     *
     * @param night      现在是不是夜里
     * @param attackedMe 这个生物刚打过我们
     */
    public static Kind classify(String typeId, boolean night, boolean attackedMe, Set<String> hostile, Set<String> fleeOnly,
                                Set<String> never, Set<String> hostileAtNight, Set<String> hostileIfAttacked) {
        String id = Rules.normalize(typeId);
        if (id.isEmpty()) {
            return Kind.IGNORE;
        }
        if (never.contains(id)) {
            return Kind.NEVER;
        }
        if (fleeOnly.contains(id)) {
            return Kind.FLEE;
        }
        if (hostile.contains(id)) {
            return Kind.HOSTILE;
        }
        if (night && hostileAtNight.contains(id)) {
            return Kind.HOSTILE;
        }
        if (attackedMe && hostileIfAttacked.contains(id)) {
            return Kind.HOSTILE;
        }
        return Kind.IGNORE;
    }

    public static Kind classify(String typeId, boolean night, boolean attackedMe, Rules rules) {
        return classify(typeId, night, attackedMe, rules.hostile(), rules.fleeOnly(), rules.never(), rules.hostileAtNight(),
                rules.hostileIfAttacked());
    }

    /** 开不开打：被怪打了，或怪到了 3 格内。 */
    public static boolean shouldEngage(double nearestHostileDist, boolean attackedByHostile) {
        return attackedByHostile || nearestHostileDist <= TRIGGER_RADIUS;
    }

    /**
     * 这一 tick 的处境。threatDist / fleeDist 为 NaN 表示没有。
     *
     * @param damageLast10s   最近 10 秒掉的血
     * @param ticksSinceShield 上次举盾到现在的 tick 数
     * @param ateThisFight    这场架已经吃过一口了
     */
    public record Situation(float health, boolean shieldInOffhand, boolean hasFood, double threatDist, double fleeDist,
                            int fightTicks, float damageLast10s, int ticksSinceShield, boolean ateThisFight,
                            boolean hasBowAndArrow, boolean canSeeTarget, boolean protecting, boolean ownerLow) {

        /** 任务单 19 的写法（没有弓、没有保护）。 */
        public Situation(float health, boolean shieldInOffhand, boolean hasFood, double threatDist, double fleeDist,
                         int fightTicks, float damageLast10s, int ticksSinceShield, boolean ateThisFight) {
            this(health, shieldInOffhand, hasFood, threatDist, fleeDist, fightTicks, damageLast10s, ticksSinceShield, ateThisFight,
                    false, true, false, false);
        }
    }

    public static Action decide(Situation s) {
        if (!Double.isNaN(s.fleeDist()) && s.fleeDist() <= FLEE_TRIGGER) {
            return Action.FLEE;
        }
        if (Double.isNaN(s.threatDist())) {
            return Action.NONE;
        }
        if (s.fightTicks() > MAX_FIGHT_TICKS || s.damageLast10s() >= OVERWHELMED_DAMAGE_10S) {
            return Action.GIVE_UP;
        }
        if (s.health() <= EAT_HEALTH && s.hasFood() && !s.ateThisFight()) {
            return Action.EAT_RUN;
        }
        if (s.health() <= SHIELD_HEALTH && s.shieldInOffhand() && s.ticksSinceShield() > SHIELD_COOLDOWN_TICKS) {
            return Action.SHIELD_BACK;
        }
        if (s.protecting() && s.ownerLow() && s.threatDist() > REACH) {
            return Action.GUARD;
        }
        if (s.threatDist() <= REACH) {
            return Action.ATTACK;
        }
        if (s.hasBowAndArrow() && s.canSeeTarget() && s.threatDist() >= BOW_MIN_DISTANCE) {
            return Action.SHOOT;
        }
        return Action.APPROACH;
    }

    /** 主人正在打的那只不抢：主人离它 3 格内、在挥手，而且主人血不低。 */
    public static boolean ownerIsFighting(double ownerDistToMob, boolean ownerSwinging, float ownerHealth) {
        return ownerDistToMob <= OWNER_FIGHTING_DISTANCE && ownerSwinging && ownerHealth > OWNER_LOW_HEALTH;
    }

    /**
     * 保护时站的位置：主人和怪连线上离主人 fraction 处（拦截用 0.4，顶在前面用 0.15）。
     * 返回 {x, y, z}；怪就在主人身上时返回主人位置。
     */
    public static double[] interceptPoint(double ox, double oy, double oz, double tx, double ty, double tz, double fraction) {
        double dx = tx - ox;
        double dy = ty - oy;
        double dz = tz - oz;
        if (Math.hypot(dx, dz) < 0.5) {
            return new double[]{ox, oy, oz};
        }
        return new double[]{ox + dx * fraction, oy + dy * fraction, oz + dz * fraction};
    }

    /** "打那只 xx"：种类对得上（都补 minecraft: 前缀比）。 */
    public static boolean huntMatches(String typeId, String kind) {
        return !Rules.normalize(kind).isEmpty() && Rules.normalize(typeId).equals(Rules.normalize(kind));
    }

    /** 弓：物品 id 是 bow；箭：任何 *arrow（普通、光灵、药水箭）。 */
    public static boolean isBow(String itemId) {
        return Rules.normalize(itemId).equals("minecraft:bow");
    }

    public static boolean isArrow(String itemId) {
        return Rules.normalize(itemId).endsWith("arrow");
    }

    /** 武器好坏：剑按档次，斧比同档的剑差一点，别的 0。 */
    public static int weaponRank(String itemId) {
        String id = Rules.normalize(itemId);
        int tier = id.contains("netherite") ? 6 : id.contains("diamond") ? 5 : id.contains("iron") ? 4 : id.contains("stone") ? 3
                : id.contains("golden") ? 2 : id.contains("wooden") ? 1 : 0;
        if (tier == 0) {
            return 0;
        }
        if (id.endsWith("_sword")) {
            return tier * 10;
        }
        if (id.endsWith("_axe")) {
            return tier * 10 - 5;
        }
        return 0;
    }
}
