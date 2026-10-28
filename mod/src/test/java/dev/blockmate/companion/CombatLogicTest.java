package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 19：自保的纯逻辑——名单判定、开不开打、每 tick 做什么、挑武器。 */
class CombatLogicTest {

    private static final Set<String> HOSTILE = Set.of("minecraft:zombie", "minecraft:skeleton");
    private static final Set<String> FLEE = Set.of("minecraft:creeper");
    private static final Set<String> NEVER = Set.of("minecraft:villager", "minecraft:player", "minecraft:iron_golem");
    private static final Set<String> AT_NIGHT = Set.of("minecraft:spider");
    private static final Set<String> IF_ATTACKED = Set.of("minecraft:spider", "minecraft:enderman");

    private static CombatLogic.Kind kind(String id, boolean night, boolean attacked) {
        return CombatLogic.classify(id, night, attacked, HOSTILE, FLEE, NEVER, AT_NIGHT, IF_ATTACKED);
    }

    @Test
    void 名单判定_在hostile上才打_never最大() {
        assertEquals(CombatLogic.Kind.HOSTILE, kind("minecraft:zombie", false, false));
        assertEquals(CombatLogic.Kind.HOSTILE, kind("zombie", false, false), "没前缀也认");
        assertEquals(CombatLogic.Kind.FLEE, kind("minecraft:creeper", true, true), "苦力怕打了我们也只躲");
        assertEquals(CombatLogic.Kind.NEVER, kind("minecraft:iron_golem", true, true), "铁傀儡打了我们也不还手");
        assertEquals(CombatLogic.Kind.NEVER, kind("minecraft:villager", true, false));
        assertEquals(CombatLogic.Kind.IGNORE, kind("minecraft:cow", true, true), "不在任何名单上就不理");
        assertEquals(CombatLogic.Kind.IGNORE, kind("", true, true));
    }

    @Test
    void 蜘蛛夜里或先动手才算怪_末影人只有先动手() {
        assertEquals(CombatLogic.Kind.IGNORE, kind("minecraft:spider", false, false), "白天没惹的蜘蛛不理");
        assertEquals(CombatLogic.Kind.HOSTILE, kind("minecraft:spider", true, false), "夜里的蜘蛛");
        assertEquals(CombatLogic.Kind.HOSTILE, kind("minecraft:spider", false, true), "白天先动手的蜘蛛");
        assertEquals(CombatLogic.Kind.IGNORE, kind("minecraft:enderman", true, false), "夜里没惹的末影人不碰");
        assertEquals(CombatLogic.Kind.HOSTILE, kind("minecraft:enderman", false, true), "激怒的末影人才打");
        Rules r = Rules.of(List.of(), List.of()).withCombat(HOSTILE, FLEE, NEVER, AT_NIGHT, IF_ATTACKED, "DEFEND");
        assertEquals("defend", r.combatMode());
        assertEquals(CombatLogic.Kind.HOSTILE, CombatLogic.classify("minecraft:skeleton", false, false, r));
        assertEquals("defend", Rules.of(List.of(), List.of()).withCombat(HOSTILE, FLEE, NEVER, AT_NIGHT, IF_ATTACKED, "瞎写").combatMode());
        assertEquals("off", Rules.of(List.of(), List.of()).withCombat(HOSTILE, FLEE, NEVER, AT_NIGHT, IF_ATTACKED, "off").combatMode());
        assertTrue(Rules.EMPTY.hostile().isEmpty(), "没收到名单什么都不打");
    }

    @Test
    void 开打条件_被打了或怪到3格内() {
        assertTrue(CombatLogic.shouldEngage(2.9, false));
        assertFalse(CombatLogic.shouldEngage(5.0, false), "5 格外的怪不主动去打");
        assertTrue(CombatLogic.shouldEngage(7.0, true), "被打了就打回去");
        assertFalse(CombatLogic.shouldEngage(Double.NaN, false));
    }

    private static CombatLogic.Situation sit(float health, boolean shield, boolean food, double threat, double flee, int ticks,
                                             float dmg10s, int sinceShield, boolean ate) {
        return new CombatLogic.Situation(health, shield, food, threat, flee, ticks, dmg10s, sinceShield, ate);
    }

    @Test
    void 每tick的决定_按优先级() {
        assertEquals(CombatLogic.Action.FLEE, CombatLogic.decide(sit(20, true, true, 2, 2.5, 10, 0, 1000, false)), "苦力怕 3 格内先躲");
        assertEquals(CombatLogic.Action.NONE, CombatLogic.decide(sit(20, true, true, Double.NaN, 7, 10, 0, 1000, false)), "没怪");
        assertEquals(CombatLogic.Action.GIVE_UP, CombatLogic.decide(sit(20, true, true, 2, Double.NaN, 601, 0, 1000, false)), "打了 30 秒");
        assertEquals(CombatLogic.Action.GIVE_UP, CombatLogic.decide(sit(12, true, true, 2, Double.NaN, 100, 8, 1000, false)), "10 秒掉 8 血");
        assertEquals(CombatLogic.Action.EAT_RUN, CombatLogic.decide(sit(6, true, true, 2, Double.NaN, 100, 2, 1000, false)));
        assertEquals(CombatLogic.Action.SHIELD_BACK, CombatLogic.decide(sit(6, true, false, 2, Double.NaN, 100, 2, 1000, false)), "没吃的就举盾");
        assertEquals(CombatLogic.Action.SHIELD_BACK, CombatLogic.decide(sit(6, true, true, 2, Double.NaN, 100, 2, 1000, true)), "这场吃过了就不再吃");
        assertEquals(CombatLogic.Action.ATTACK, CombatLogic.decide(sit(8, true, false, 2, Double.NaN, 100, 2, 50, false)), "刚举过盾（5 秒内）不再举");
        assertEquals(CombatLogic.Action.ATTACK, CombatLogic.decide(sit(8, false, false, 2, Double.NaN, 100, 2, 1000, false)), "没盾就打");
        assertEquals(CombatLogic.Action.ATTACK, CombatLogic.decide(sit(20, false, false, 3.0, Double.NaN, 100, 0, 1000, false)));
        assertEquals(CombatLogic.Action.APPROACH, CombatLogic.decide(sit(20, false, false, 6.0, Double.NaN, 100, 0, 1000, false)), "够不着就靠近");
    }

    @Test
    void 挑武器_剑按档次_斧差一点() {
        assertTrue(CombatLogic.weaponRank("minecraft:diamond_sword") > CombatLogic.weaponRank("minecraft:iron_sword"));
        assertTrue(CombatLogic.weaponRank("minecraft:iron_sword") > CombatLogic.weaponRank("minecraft:iron_axe"));
        assertTrue(CombatLogic.weaponRank("minecraft:iron_axe") > CombatLogic.weaponRank("minecraft:stone_sword"));
        assertEquals(0, CombatLogic.weaponRank("minecraft:iron_pickaxe"), "镐不是武器");
        assertEquals(0, CombatLogic.weaponRank("minecraft:bread"));
        assertEquals(10, CombatLogic.weaponRank("wooden_sword"));
    }
}
