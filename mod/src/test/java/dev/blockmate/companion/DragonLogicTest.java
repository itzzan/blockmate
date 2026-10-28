package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 23：打龙细则的优先级、笼子判断、柱子背面、眼的方向。 */
class DragonLogicTest {

    private static DragonLogic.Situation sit(boolean breath, float health, boolean retreating, double dragonDist, boolean bow, boolean crystal,
                                            boolean owner, int sinceDamage, int fightTicks, boolean tired) {
        return new DragonLogic.Situation(breath, health, retreating, dragonDist, bow, crystal, owner, sinceDamage, fightTicks, tired);
    }

    @Test
    void 优先级_龙息最高_血少撤_龙近砍_有弓射水晶_否则守() {
        assertEquals(DragonLogic.Action.DODGE_BREATH, DragonLogic.decide(sit(true, 20, false, 3, true, true, true, 0, 100, false)));
        assertEquals(DragonLogic.Action.RETREAT, DragonLogic.decide(sit(false, 8, false, 3, true, true, true, 0, 100, false)), "血 8 撤，哪怕龙在跟前");
        assertEquals(DragonLogic.Action.RETREAT, DragonLogic.decide(sit(false, 12, true, 30, true, true, true, 0, 100, false)), "撤着的要回到 14 才上");
        assertEquals(DragonLogic.Action.MELEE, DragonLogic.decide(sit(false, 14, true, 5, true, true, true, 0, 100, false)), "回到 14 且龙在 6 格内就砍");
        assertEquals(DragonLogic.Action.SHOOT_CRYSTAL, DragonLogic.decide(sit(false, 20, false, 30, true, true, true, 0, 100, false)));
        assertEquals(DragonLogic.Action.GUARD_OWNER, DragonLogic.decide(sit(false, 20, false, 30, false, true, true, 0, 100, false)), "没弓不碰水晶，守主人");
        assertEquals(DragonLogic.Action.GUARD_OWNER, DragonLogic.decide(sit(false, 20, false, 30, true, false, true, 0, 100, false)), "没水晶可射守主人");
        assertEquals(DragonLogic.Action.GUARD_PORTAL, DragonLogic.decide(sit(false, 20, false, 30, true, false, false, 0, 100, false)), "主人不在守传送门");
        assertEquals(DragonLogic.Action.SHOOT_CRYSTAL, DragonLogic.decide(sit(false, 20, false, 30, true, true, false, 500, 100, false)), "主人不在但刚造成过伤害照打");
        assertEquals(DragonLogic.Action.GUARD_PORTAL, DragonLogic.decide(sit(false, 20, false, 30, true, true, false, 1201, 100, false)), "主人不在 60 秒没打到就守门");
        assertEquals(DragonLogic.Action.MELEE, DragonLogic.decide(sit(false, 20, false, 5, true, true, false, 1201, 100, false)), "龙俯冲到跟前照样砍");
        assertEquals(DragonLogic.Action.TIRED, DragonLogic.decide(sit(false, 20, false, 30, true, true, true, 0, 18001, false)), "15 分钟说打不动");
        assertEquals(DragonLogic.Action.GUARD_OWNER, DragonLogic.decide(sit(false, 20, false, 30, true, true, true, 0, 18001, true)), "说过了就只守");
        assertEquals(DragonLogic.Action.MELEE, DragonLogic.decide(sit(false, 20, false, 5, true, true, true, 0, 18001, true)), "打不动了龙到跟前还是砍");
    }

    @Test
    void 笼子_柱子背面_眼的方向() {
        Set<String> bars = Set.of("1,66,0", "0,66,1");
        DragonLogic.BarsProbe p = (x, y, z) -> bars.contains(x + "," + y + "," + z);
        assertTrue(DragonLogic.caged(p, 0, 65, 0), "上面一格旁边有铁栏杆");
        assertFalse(DragonLogic.caged(p, 10, 65, 10));
        double[] b = DragonLogic.behindPillar(100, 0, 0, 0);
        assertEquals(103, b[0], 1e-9);
        assertEquals(0, b[1], 1e-9);
        double[] same = DragonLogic.behindPillar(5, 5, 5, 5);
        assertEquals(8, same[0], 1e-9);
        assertEquals(0, DragonLogic.yawOf(0, 10), 1e-9, "往南 yaw 0");
        assertEquals(90, DragonLogic.yawOf(-10, 0), 1e-9, "往西 yaw 90");
        assertEquals(-90, DragonLogic.yawOf(10, 0), 1e-9, "往东 yaw -90");
        assertTrue(DragonLogic.eyeNear(0.3, 0.4));
        assertFalse(DragonLogic.eyeNear(3, 4));
    }
}
