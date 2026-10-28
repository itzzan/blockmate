package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 任务单 21：带路的纯逻辑——等人的距离、前方岩浆和断崖。 */
class LeadLogicTest {

    /** 假世界：y=63 以下全是地，另外可以挖坑、放岩浆。 */
    private static final class World implements LeadLogic.Probe {
        final Set<String> lava = new HashSet<>();
        final Set<String> holes = new HashSet<>();

        World lava(int x, int y, int z) {
            lava.add(x + "," + y + "," + z);
            return this;
        }

        World hole(int x, int z, int depth) {
            for (int d = 0; d < depth; d++) {
                holes.add(x + "," + (63 - d) + "," + z);
            }
            return this;
        }

        @Override
        public boolean isLava(int x, int y, int z) {
            return lava.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return y <= 63 && !holes.contains(x + "," + y + "," + z) && !isLava(x, y, z);
        }
    }

    @Test
    void 落后距离_主世界32和16_下界末地减半() {
        assertEquals(32.0, LeadLogic.farLimit("minecraft:overworld"));
        assertEquals(16.0, LeadLogic.nearLimit("minecraft:overworld"));
        assertEquals(32.0, LeadLogic.farLimit(null));
        assertEquals(16.0, LeadLogic.farLimit("minecraft:the_nether"));
        assertEquals(8.0, LeadLogic.nearLimit("minecraft:the_end"));
    }

    @Test
    void 前方4格内有岩浆就停_5格外不管_身后不管() {
        World w = new World().lava(3, 63, 0);
        assertEquals("lava", LeadLogic.dangerAhead(w, 0, 64, 0, 1, 0), "脚下一格平面的岩浆");
        assertNull(LeadLogic.dangerAhead(w, 0, 64, 0, -1, 0), "身后的不管");
        assertNull(LeadLogic.dangerAhead(new World().lava(5, 63, 0), 0, 64, 0, 1, 0), "5 格外不管");
        assertEquals("lava", LeadLogic.dangerAhead(new World().lava(0, 64, 2), 0, 64, 0, 0, 1), "脚平面的岩浆");
        assertEquals("lava", LeadLogic.dangerAhead(new World().lava(2, 63, 2), 0, 64, 0, 1, 1), "斜着走也认");
        assertNull(LeadLogic.dangerAhead(w, 0, 64, 0, 0, 0), "没在动、没方向就不判");
    }

    @Test
    void 前面空4格以上算断崖_下楼梯不算_远处的坡不算() {
        assertEquals("cliff", LeadLogic.dangerAhead(new World().hole(2, 0, 5), 0, 64, 0, 1, 0), "前面 2 格是 5 格深的坑");
        assertEquals("cliff", LeadLogic.dangerAhead(new World().hole(1, 0, 10), 0, 64, 0, 1, 0));
        assertNull(LeadLogic.dangerAhead(new World().hole(2, 0, 4), 0, 64, 0, 1, 0), "正好 4 格不算");
        assertNull(LeadLogic.dangerAhead(new World().hole(2, 0, 1), 0, 64, 0, 1, 0), "下一级台阶");
        assertNull(LeadLogic.dangerAhead(new World().hole(4, 0, 9), 0, 64, 0, 1, 0), "断崖只看前 3 格");
        assertNull(LeadLogic.dangerAhead(new World(), 0, 64, 0, 1, 0), "平地");
        assertEquals(5, LeadLogic.dropBelow(new World().hole(0, 0, 9), 0, 64, 0), "最多数到 5");
        assertEquals(0, LeadLogic.dropBelow(new World(), 0, 64, 0));
    }
}
