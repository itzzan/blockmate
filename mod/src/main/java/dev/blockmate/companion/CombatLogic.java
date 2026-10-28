package dev.blockmate.companion;

import java.util.Set;

/**
 * 自保的纯逻辑（任务单 19，guide-3 §2.1）：名单怎么判、什么时候算被攻击、每 tick 该做哪一样。
 * 全是写死的规则，不经过模型；不碰游戏类，方便测试。感知和动手在 {@link Combat}。
 */
public final class CombatLogic {

    /** 一个生物对我们来说是什么。 */
    public enum Kind { HOSTILE, FLEE, NEVER, IGNORE }

    /** 这一 tick 该做什么（按优先级从高到低判）。 */
    public enum Action { NONE, FLEE, GIVE_UP, EAT_RUN, SHIELD_BACK, ATTACK, APPROACH }

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
                            int fightTicks, float damageLast10s, int ticksSinceShield, boolean ateThisFight) {
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
        return s.threatDist() <= REACH ? Action.ATTACK : Action.APPROACH;
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
