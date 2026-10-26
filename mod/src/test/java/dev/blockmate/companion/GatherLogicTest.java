package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 13：挖够了没有、多久没进展、离主人太远——不依赖游戏的判定。 */
class GatherLogicTest {

    @Test
    void 按掉落物计数_够数就完成() {
        GatherLogic.MineProgress p = new GatherLogic.MineProgress(5, 3, 400, 0);
        assertEquals(GatherLogic.MineProgress.Verdict.CONTINUE, p.feed(5, 10));
        assertEquals(GatherLogic.MineProgress.Verdict.CONTINUE, p.feed(7, 100));
        assertEquals(2, p.mined());
        assertEquals(GatherLogic.MineProgress.Verdict.DONE, p.feed(8, 150));
        assertEquals(3, p.mined());
        assertEquals(GatherLogic.MineProgress.Verdict.DONE, p.feed(12, 160), "一下多了好几个也算够");
    }

    @Test
    void 二十秒没多一个算卡住_有进展就重新计时() {
        GatherLogic.MineProgress p = new GatherLogic.MineProgress(0, 10, 400, 0);
        assertEquals(GatherLogic.MineProgress.Verdict.CONTINUE, p.feed(0, 399));
        assertEquals(GatherLogic.MineProgress.Verdict.STALLED, p.feed(0, 400));
        GatherLogic.MineProgress q = new GatherLogic.MineProgress(0, 10, 400, 0);
        assertEquals(GatherLogic.MineProgress.Verdict.CONTINUE, q.feed(1, 390));
        assertEquals(GatherLogic.MineProgress.Verdict.CONTINUE, q.feed(1, 700));
        assertEquals(GatherLogic.MineProgress.Verdict.STALLED, q.feed(1, 790));
        assertEquals(1, q.mined(), "卡住时也知道已经挖了几个");
    }

    @Test
    void 离主人太远() {
        assertFalse(GatherLogic.tooFarFromOwner(false, false, 0), "主人不在服务器里不限制");
        assertTrue(GatherLogic.tooFarFromOwner(true, false, 0), "主人在服里却看不见 = 超出同步范围");
        assertFalse(GatherLogic.tooFarFromOwner(true, true, 64));
        assertTrue(GatherLogic.tooFarFromOwner(true, true, 64.5));
    }

    @Test
    void 掉落对照() {
        Rules r = Rules.of(List.of("stone", "oak_log"), List.of(), Map.of("stone", "cobblestone", "minecraft:coal_ore", "coal"));
        assertEquals("minecraft:cobblestone", r.dropOf("minecraft:stone"));
        assertEquals("minecraft:cobblestone", r.dropOf("stone"));
        assertEquals("minecraft:oak_log", r.dropOf("minecraft:oak_log"), "没写的和方块同名");
        assertEquals("minecraft:coal", r.dropOf("coal_ore"));
        assertEquals("minecraft:dirt", Rules.EMPTY.dropOf("dirt"));
    }
}
