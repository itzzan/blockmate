package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 6.4 第 1、2、4 条里不依赖游戏的判定逻辑。 */
class ExecutorLogicTest {

    @Test
    void 五秒不动算卡住_动了就重新计时() {
        StuckDetector s = new StuckDetector(100, 0.1);
        assertFalse(s.feed(0, 64, 0, 0));
        assertFalse(s.feed(0.05, 64, 0, 99), "挪了不到 0.1 格不算动");
        assertTrue(s.feed(0.05, 64, 0, 100));
        s.reset();
        assertFalse(s.feed(0, 64, 0, 200));
        assertFalse(s.feed(1, 64, 0, 250));
        assertFalse(s.feed(1, 64, 0, 349));
        assertTrue(s.feed(1, 64, 0, 350));
    }

    @Test
    void 停_静止才算_最多三次() {
        StopTracker t = new StopTracker(20, 3, 0);
        assertEquals(StopTracker.Verdict.WAIT, t.feed(false, 5));
        assertEquals(StopTracker.Verdict.RETRY, t.feed(false, 20));
        assertEquals(2, t.attempts());
        assertEquals(StopTracker.Verdict.WAIT, t.feed(false, 30));
        assertEquals(StopTracker.Verdict.RETRY, t.feed(false, 40));
        assertEquals(3, t.attempts());
        assertEquals(StopTracker.Verdict.GIVE_UP, t.feed(false, 60));
        assertEquals(3, t.attempts());

        StopTracker ok = new StopTracker(20, 3, 0);
        assertEquals(StopTracker.Verdict.DONE_STATIONARY, ok.feed(true, 3));
        assertEquals(1, ok.attempts());
    }

    @Test
    void 跟随_十秒看不到主人算丢_换维度算另一维度() {
        FollowWatch w = new FollowWatch(200, 8, 0, "minecraft:overworld");
        assertEquals(FollowWatch.Outcome.OK, w.feed(true, "minecraft:overworld", 10));
        assertEquals(FollowWatch.Outcome.OK, w.feed(false, "minecraft:overworld", 209));
        assertEquals(FollowWatch.Outcome.TARGET_LOST, w.feed(false, "minecraft:overworld", 210));

        FollowWatch w2 = new FollowWatch(200, 8, 0, "minecraft:overworld");
        assertEquals(FollowWatch.Outcome.TARGET_OTHER_DIM, w2.feed(true, "minecraft:the_nether", 5));
    }

    @Test
    void 跟随_距离每变化八格发一次progress() {
        FollowWatch w = new FollowWatch(200, 8, 0, "minecraft:overworld");
        assertFalse(w.progressDue(3.0), "第一次只记基准");
        assertFalse(w.progressDue(10.0));
        assertTrue(w.progressDue(11.5));
        assertFalse(w.progressDue(5.0));
        assertTrue(w.progressDue(3.0));
    }
}
