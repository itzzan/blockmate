package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 22：搭门的纯逻辑——找平地、放的顺序和每块靠谁、站位、够不够得着。 */
class PortalLogicTest {

    private static final List<List<Integer>> FRAME = List.of(List.of(1, 0, 0), List.of(2, 0, 0), List.of(0, 1, 0), List.of(0, 2, 0),
            List.of(0, 3, 0), List.of(3, 1, 0), List.of(3, 2, 0), List.of(3, 3, 0), List.of(1, 4, 0), List.of(2, 4, 0));
    private static final List<List<Integer>> CORNERS = List.of(List.of(0, 0, 0), List.of(3, 0, 0), List.of(0, 4, 0), List.of(3, 4, 0));
    private static final List<List<Integer>> INSIDE = List.of(List.of(1, 1, 0), List.of(2, 1, 0), List.of(1, 2, 0), List.of(2, 2, 0),
            List.of(1, 3, 0), List.of(2, 3, 0));

    /** 假世界：y ≤ 63 是地，上面全空；可以竖墙。 */
    private static final class World implements PortalLogic.Probe {
        final Set<String> walls = new HashSet<>();

        World wall(int x, int z) {
            for (int y = 64; y < 70; y++) {
                walls.add(x + "," + y + "," + z);
            }
            return this;
        }

        @Override
        public boolean isFree(int x, int y, int z) {
            return y > 63 && !walls.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean isSolidTop(int x, int y, int z) {
            return y == 63 || walls.contains(x + "," + y + "," + z);
        }
    }

    @Test
    void 平地上找得到_不把自己围在门框里_地面要实() {
        PortalLogic.Cell feet = new PortalLogic.Cell(0, 64, 0);
        Optional<PortalLogic.Site> site = PortalLogic.findSite(new World(), feet, FRAME, CORNERS, INSIDE);
        assertTrue(site.isPresent());
        PortalLogic.Site s = site.get();
        assertEquals(64, s.origin().y());
        List<PortalLogic.Cell> cells = PortalLogic.absAll(s.origin(), FRAME, s.axis());
        assertFalse(cells.contains(feet));
        assertFalse(cells.contains(feet.plus(0, 1, 0)));
        for (PortalLogic.Cell c : cells) {
            assertTrue(c.y() >= 64, "都在地面上");
        }
        // 把脚平面以上整片都堵住：找不到
        World blocked = new World();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                blocked.wall(x, z);
            }
        }
        assertTrue(PortalLogic.findSite(blocked, feet, FRAME, CORNERS, INSIDE).isEmpty());
    }

    @Test
    void 沿x放不下就转90度() {
        // 沿 x 方向每隔 2 格竖一堵墙，4 宽的门框放不下；沿 z 方向畅通
        World w = new World();
        for (int x = -8; x <= 8; x += 2) {
            for (int z = -8; z <= 8; z++) {
                if (x != 0) {
                    w.wall(x, z);
                }
            }
        }
        PortalLogic.Cell feet = new PortalLogic.Cell(0, 64, 0);
        PortalLogic.Site s = PortalLogic.findSite(w, feet, FRAME, CORNERS, INSIDE).orElseThrow();
        assertEquals(PortalLogic.Axis.Z, s.axis());
        for (PortalLogic.Cell c : PortalLogic.absAll(s.origin(), FRAME, s.axis())) {
            assertEquals(s.origin().x(), c.x(), "转过来以后门框在同一个 x 上");
        }
    }

    @Test
    void 放的顺序_每块都有靠的_先贴地再往上最后顶排() {
        PortalLogic.Cell origin = new PortalLogic.Cell(10, 64, 10);
        List<PortalLogic.Cell> all = new java.util.ArrayList<>(PortalLogic.absAll(origin, FRAME, PortalLogic.Axis.X));
        all.addAll(PortalLogic.absAll(origin, CORNERS, PortalLogic.Axis.X));
        List<PortalLogic.Placement> order = PortalLogic.order(all, origin, PortalLogic.Axis.X);
        assertEquals(14, order.size(), "10 块门框 + 4 个角都排进去了");
        Set<PortalLogic.Cell> placed = new HashSet<>();
        for (PortalLogic.Placement p : order) {
            boolean onGround = p.target().y() == 64 && p.support().equals(p.target().plus(0, -1, 0));
            assertTrue(onGround || placed.contains(p.support()), "放 " + p.target() + " 时靠的 " + p.support() + " 得已经在了");
            assertEquals(1, Math.abs(p.dx()) + Math.abs(p.dy()) + Math.abs(p.dz()), "只能贴一个面");
            placed.add(p.target());
        }
        assertEquals(64, order.get(0).target().y(), "先放贴地那行");
        assertEquals(68, order.get(order.size() - 1).target().y(), "顶排最后");
        // 没有角就放不全：顶排和两边的柱子都没得靠
        List<PortalLogic.Placement> noCorners = PortalLogic.order(PortalLogic.absAll(origin, FRAME, PortalLogic.Axis.X), origin, PortalLogic.Axis.X);
        assertTrue(noCorners.size() < 10, "没角时有块放不上：" + noCorners.size());
    }

    @Test
    void 站位和够不够得着() {
        PortalLogic.Site s = new PortalLogic.Site(new PortalLogic.Cell(10, 64, 10), PortalLogic.Axis.X);
        List<PortalLogic.Cell> spots = PortalLogic.standingSpots(s);
        assertEquals(2, spots.size());
        assertEquals(new PortalLogic.Cell(11, 64, 8), spots.get(0));
        // 站在门框正前方 2 格，顶上角也够得着；站 6 格外就不行
        assertTrue(PortalLogic.inReach(11.5, 65.6, 8.5, new PortalLogic.Cell(13, 68, 10)));
        assertFalse(PortalLogic.inReach(11.5, 65.6, 4.5, new PortalLogic.Cell(13, 68, 10)));
        PortalLogic.Site z = new PortalLogic.Site(new PortalLogic.Cell(10, 64, 10), PortalLogic.Axis.Z);
        assertEquals(new PortalLogic.Cell(8, 64, 11), PortalLogic.standingSpots(z).get(0));
        assertEquals(new PortalLogic.Cell(10, 65, 13), PortalLogic.abs(z.origin(), List.of(3, 1, 0), PortalLogic.Axis.Z), "转 90 度：dx 变 dz");
    }
}
