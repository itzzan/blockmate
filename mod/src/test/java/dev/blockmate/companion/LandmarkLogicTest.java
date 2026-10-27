package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 17：地标扫描的纯逻辑——统计怎么变成"看见了什么"、同种 100 格内只报一次。 */
class LandmarkLogicTest {

    private static LandmarkLogic.Tally tally(String key, int n, int x, int y, int z) {
        LandmarkLogic.Tally t = new LandmarkLogic.Tally();
        for (int i = 0; i < n; i++) {
            t.hit(key, x + i, y, z);
        }
        return t;
    }

    @Test
    void 钟是村庄_没钟两个村民也是村庄_一个不算() {
        List<LandmarkLogic.Found> a = LandmarkLogic.classify(tally(LandmarkLogic.BELL, 1, 10, 64, 20), LandmarkLogic.DIM_OVERWORLD, 0, null);
        assertEquals(1, a.size());
        assertEquals("village", a.get(0).kind());
        assertEquals(10.5, a.get(0).x());
        assertFalse(a.get(0).danger());
        List<LandmarkLogic.Found> b = LandmarkLogic.classify(new LandmarkLogic.Tally(), LandmarkLogic.DIM_OVERWORLD, 2, new double[]{1, 2, 3});
        assertEquals("village", b.get(0).kind());
        assertEquals(2.0, b.get(0).y());
        assertTrue(LandmarkLogic.classify(new LandmarkLogic.Tally(), LandmarkLogic.DIM_OVERWORLD, 1, new double[]{1, 2, 3}).isEmpty());
    }

    @Test
    void 传送门按维度分_要塞只在下界认_遗迹要塞要两种方块() {
        assertEquals("portal_overworld", LandmarkLogic.classify(tally(LandmarkLogic.NETHER_PORTAL, 1, 0, 0, 0), LandmarkLogic.DIM_OVERWORLD, 0, null).get(0).kind());
        assertEquals("portal_nether", LandmarkLogic.classify(tally(LandmarkLogic.NETHER_PORTAL, 1, 0, 0, 0), LandmarkLogic.DIM_NETHER, 0, null).get(0).kind());
        assertTrue(LandmarkLogic.classify(tally(LandmarkLogic.NETHER_PORTAL, 1, 0, 0, 0), LandmarkLogic.DIM_END, 0, null).isEmpty(), "末地没有下界传送门");
        assertTrue(LandmarkLogic.classify(tally(LandmarkLogic.NETHER_BRICKS, 30, 0, 0, 0), LandmarkLogic.DIM_OVERWORLD, 0, null).isEmpty(), "主世界的下界砖是人造的");
        assertTrue(LandmarkLogic.classify(tally(LandmarkLogic.NETHER_BRICKS, 5, 0, 0, 0), LandmarkLogic.DIM_NETHER, 0, null).isEmpty(), "几块零散的不算");
        assertEquals("fortress", LandmarkLogic.classify(tally(LandmarkLogic.NETHER_BRICKS, 20, 0, 0, 0), LandmarkLogic.DIM_NETHER, 0, null).get(0).kind());
        assertEquals("end_portal", LandmarkLogic.classify(tally(LandmarkLogic.END_PORTAL_FRAME, 1, 0, 0, 0), LandmarkLogic.DIM_OVERWORLD, 0, null).get(0).kind());
        assertTrue(LandmarkLogic.classify(tally(LandmarkLogic.MOSSY_STONE_BRICKS, 3, 0, 0, 0), LandmarkLogic.DIM_OVERWORLD, 0, null).isEmpty(), "只有苔石砖不算");
        LandmarkLogic.Tally both = tally(LandmarkLogic.MOSSY_STONE_BRICKS, 3, 0, 30, 0);
        both.hit(LandmarkLogic.STONE_BRICK_STAIRS, 1, 30, 1);
        assertEquals("stronghold", LandmarkLogic.classify(both, LandmarkLogic.DIM_OVERWORLD, 0, null).get(0).kind());
    }

    @Test
    void 岩浆够多才是危险() {
        assertTrue(LandmarkLogic.classify(tally(LandmarkLogic.LAVA_NEAR, 19, 0, 0, 0), LandmarkLogic.DIM_OVERWORLD, 0, null).isEmpty());
        List<LandmarkLogic.Found> f = LandmarkLogic.classify(tally(LandmarkLogic.LAVA_NEAR, 20, 0, 0, 0), LandmarkLogic.DIM_OVERWORLD, 0, null);
        assertEquals("lava", f.get(0).kind());
        assertTrue(f.get(0).danger());
    }

    @Test
    void 同种地标100格内只报一次_危险32格_换维度重新算() {
        LandmarkLogic logic = new LandmarkLogic();
        LandmarkLogic.Found v = new LandmarkLogic.Found("village", false, 0, 64, 0);
        assertTrue(logic.shouldReport(v, LandmarkLogic.DIM_OVERWORLD));
        assertFalse(logic.shouldReport(new LandmarkLogic.Found("village", false, 60, 64, 60), LandmarkLogic.DIM_OVERWORLD), "85 格内同种不再报");
        assertTrue(logic.shouldReport(new LandmarkLogic.Found("village", false, 120, 64, 0), LandmarkLogic.DIM_OVERWORLD), "120 格外是另一个村庄");
        assertTrue(logic.shouldReport(new LandmarkLogic.Found("portal_overworld", false, 1, 64, 1), LandmarkLogic.DIM_OVERWORLD), "别的种类不受影响");
        assertTrue(logic.shouldReport(new LandmarkLogic.Found("village", false, 0, 64, 0), LandmarkLogic.DIM_NETHER), "维度不同另算");
        LandmarkLogic.Found lava = new LandmarkLogic.Found("lava", true, 0, 10, 0);
        assertTrue(logic.shouldReport(lava, LandmarkLogic.DIM_OVERWORLD));
        assertFalse(logic.shouldReport(new LandmarkLogic.Found("lava", true, 20, 10, 20), LandmarkLogic.DIM_OVERWORLD));
        assertTrue(logic.shouldReport(new LandmarkLogic.Found("lava", true, 40, 10, 0), LandmarkLogic.DIM_OVERWORLD));
        assertEquals(6, logic.reportedCount());
    }
}
