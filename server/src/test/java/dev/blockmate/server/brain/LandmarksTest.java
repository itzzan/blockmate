package dev.blockmate.server.brain;

import dev.blockmate.protocol.Pos;
import dev.blockmate.server.memory.StoredWorldFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 任务单 17：地标的中文名、主人的词、方向和距离。 */
class LandmarksTest {

    private static StoredWorldFact fact(String kind, String name, String dim, double x, double z, long ts) {
        return new StoredWorldFact("id-" + ts, kind, name, dim, x, 64, z, ts, "seen");
    }

    @Test
    void 方向八个方位_北是负z() {
        assertEquals("北", Landmarks.direction(0, -10));
        assertEquals("南", Landmarks.direction(0, 10));
        assertEquals("东", Landmarks.direction(10, 0));
        assertEquals("西", Landmarks.direction(-10, 0));
        assertEquals("东北", Landmarks.direction(10, -10));
        assertEquals("西南", Landmarks.direction(-10, 10));
        assertEquals("东南", Landmarks.direction(7, 8));
        assertEquals("西北", Landmarks.direction(-8, -7));
    }

    @Test
    void 主人的词认成哪一种_按维度分() {
        assertEquals("village", Landmarks.kindOf("村庄", "minecraft:overworld"));
        assertEquals("village", Landmarks.kindOf("村子", null));
        assertEquals("mine_site", Landmarks.kindOf("矿洞", null));
        assertEquals("portal_overworld", Landmarks.kindOf("传送门", "minecraft:overworld"));
        assertEquals("portal_nether", Landmarks.kindOf("传送门", "minecraft:the_nether"));
        assertEquals("stronghold", Landmarks.kindOf("要塞", "minecraft:overworld"));
        assertEquals("fortress", Landmarks.kindOf("要塞", "minecraft:the_nether"));
        assertEquals("fortress", Landmarks.kindOf("下界要塞", "minecraft:overworld"));
        assertEquals("end_portal", Landmarks.kindOf("末地传送门", null));
        assertEquals("village", Landmarks.kindOf("village", null), "英文 id 也认");
        assertNull(Landmarks.kindOf("我的秘密基地", null), "认不出的当普通地点名");
        assertNull(Landmarks.kindOf("家", null), "家走 places，不是地标");
        assertEquals("村庄", Landmarks.kindName("village"));
        assertEquals("岩浆", Landmarks.kindName("danger"));
    }

    @Test
    void 描述带方向距离_不同维度只写维度_主人起的名字带上() {
        Pos me = new Pos(100, 64, 100);
        assertEquals("村庄（西北 57 格）", Landmarks.describe(fact("village", null, "minecraft:overworld", 60, 60, 1), me, "minecraft:overworld"));
        assertEquals("村庄（就在这儿）", Landmarks.describe(fact("village", null, "minecraft:overworld", 101, 102, 1), me, "minecraft:overworld"));
        assertEquals("下界要塞（下界）", Landmarks.describe(fact("fortress", null, "minecraft:the_nether", 0, 0, 1), me, "minecraft:overworld"));
        assertEquals("老矿（矿点）（东 50 格）", Landmarks.describe(fact("mine_site", "老矿", "minecraft:overworld", 150, 100, 1), me, "minecraft:overworld"));
        assertEquals("村庄", Landmarks.describe(fact("village", null, "minecraft:overworld", 60, 60, 1), null, null), "不知道自己在哪就只报名字");
    }

    @Test
    void 最近的十条_同维度按距离_别的维度排后面() {
        Pos me = new Pos(0, 64, 0);
        List<StoredWorldFact> facts = List.of(
                fact("village", null, "minecraft:overworld", 0, -200, 1),
                fact("fortress", null, "minecraft:the_nether", 0, 0, 2),
                fact("mine_site", null, "minecraft:overworld", 10, 0, 3),
                fact("portal_overworld", null, "minecraft:overworld", -30, -30, 4));
        assertEquals(List.of("矿点（东 10 格）", "主世界的传送门（西北 42 格）", "村庄（北 200 格）", "下界要塞（下界）"),
                Landmarks.nearest(facts, me, "minecraft:overworld", 10));
        assertEquals(2, Landmarks.nearest(facts, me, "minecraft:overworld", 2).size());
        assertEquals(List.of("主世界的传送门", "矿点", "下界要塞", "村庄"), Landmarks.nearest(facts, null, null, 10), "不知道位置就按记录时间倒序");
    }
}
