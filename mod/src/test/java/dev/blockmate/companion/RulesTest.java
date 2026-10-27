package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 12：白名单判断和开关记账，不依赖游戏。 */
class RulesTest {

    @Test
    void 在清单里才能挖_能放() {
        Rules r = Rules.of(List.of("minecraft:oak_log", "Stone", " minecraft:coal_ore "), List.of("crafting_table", "minecraft:torch"));
        assertTrue(r.received());
        assertTrue(r.canMine("minecraft:oak_log"));
        assertTrue(r.canMine("oak_log"), "没写命名空间也认");
        assertTrue(r.canMine("minecraft:stone"), "清单里的大小写、空格都整理过");
        assertFalse(r.canMine("minecraft:chest"), "箱子不在清单");
        assertFalse(r.canMine("minecraft:oak_planks"), "木板是人工的，不在清单");
        assertTrue(r.canPlace("minecraft:crafting_table"));
        assertTrue(r.canPlace("torch"));
        assertFalse(r.canPlace("minecraft:furnace"));
        assertFalse(r.canPlace("minecraft:oak_log"), "能挖不等于能放");
        assertEquals(3, r.mineableCount());
        assertEquals(2, r.placeableCount());
    }

    @Test
    void 要镐的方块() {
        Rules r = Rules.of(List.of("stone", "dirt"), List.of(), null, null, List.of("stone", "minecraft:furnace"));
        assertTrue(r.needsPickaxe("minecraft:stone"));
        assertTrue(r.needsPickaxe("furnace"));
        assertFalse(r.needsPickaxe("minecraft:dirt"));
        assertFalse(Rules.EMPTY.needsPickaxe("minecraft:stone"), "没发清单就当不用镐（白名单那关本来就过不了）");
    }

    @Test
    void 没收到清单全不许() {
        assertFalse(Rules.EMPTY.received());
        assertFalse(Rules.EMPTY.canMine("minecraft:oak_log"));
        assertFalse(Rules.EMPTY.canPlace("minecraft:torch"));
        Rules empty = Rules.of(List.of(), List.of());
        assertTrue(empty.received());
        assertFalse(empty.canMine("minecraft:oak_log"), "后台给了空清单也是全不许");
        assertFalse(Rules.of(null, null).canMine(""), "空字符串不算任何方块");
    }

    @Test
    void 开关记账_只在变化时报变化() {
        GuardState g = new GuardState();
        assertFalse(g.breakOpen());
        assertTrue(g.openBreak(List.of("minecraft:oak_log")));
        assertFalse(g.openBreak(List.of("minecraft:oak_log")), "同样的清单再开一次不算变化");
        assertTrue(g.openBreak(List.of("minecraft:oak_log", "minecraft:stone")));
        assertTrue(g.breakOpen());
        assertFalse(g.placeOpen());
        assertTrue(g.openPlace());
        assertFalse(g.openPlace());
        assertEquals("break=[minecraft:oak_log, minecraft:stone] place=true", g.describe());
        assertTrue(g.closeAll());
        assertFalse(g.breakOpen());
        assertFalse(g.placeOpen());
        assertFalse(g.closeAll(), "已经关着再关不算变化");
        assertEquals("break=[] place=false", g.describe());
    }
}
