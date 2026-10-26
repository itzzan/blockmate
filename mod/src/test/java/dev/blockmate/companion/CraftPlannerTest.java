package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 14：按配方和背包算每个格子放什么、缺什么；脚边选位。 */
class CraftPlannerTest {

    private static CraftPlanner.Cell c(String... ids) {
        return new CraftPlanner.Cell(new java.util.LinkedHashSet<>(List.of(ids)));
    }

    private static final CraftPlanner.Cell E = new CraftPlanner.Cell(Set.of());

    @Test
    void 木板_一个原木一格() {
        List<CraftPlanner.Cell> cells = CraftPlanner.layoutShapeless(List.of(c("minecraft:oak_log", "minecraft:birch_log")), 2, 2);
        CraftPlanner.Plan p = CraftPlanner.plan(cells, Map.of("minecraft:birch_log", 3));
        assertTrue(p.ok());
        assertEquals(java.util.Arrays.asList("minecraft:birch_log", null, null, null), p.assignment(), "橡木没有就用桦木");
    }

    @Test
    void 木棍_两格竖着_材料不够报缺() {
        List<CraftPlanner.Cell> shaped = List.of(c("minecraft:oak_planks"), c("minecraft:oak_planks"));
        List<CraftPlanner.Cell> cells = CraftPlanner.layout(shaped, 1, 2, 2, 2);
        assertEquals(4, cells.size());
        assertTrue(cells.get(1).empty() && cells.get(3).empty(), "1×2 的配方铺进 2×2 左上对齐");
        CraftPlanner.Plan ok = CraftPlanner.plan(cells, Map.of("minecraft:oak_planks", 2));
        assertEquals(java.util.Arrays.asList("minecraft:oak_planks", null, "minecraft:oak_planks", null), ok.assignment());
        CraftPlanner.Plan bad = CraftPlanner.plan(cells, Map.of("minecraft:oak_planks", 1));
        assertFalse(bad.ok());
        assertEquals(Map.of("minecraft:oak_planks", 1), bad.missing(), "缺 1 块木板");
    }

    @Test
    void 工作台_四格_配方比格子大就不行() {
        List<CraftPlanner.Cell> shaped = List.of(c("minecraft:oak_planks"), c("minecraft:oak_planks"), c("minecraft:oak_planks"), c("minecraft:oak_planks"));
        List<CraftPlanner.Cell> cells = CraftPlanner.layout(shaped, 2, 2, 2, 2);
        CraftPlanner.Plan p = CraftPlanner.plan(cells, Map.of("minecraft:oak_planks", 4));
        assertTrue(p.ok());
        assertNull(CraftPlanner.layout(List.of(c("a"), c("a"), c("a"), c("a"), c("a"), c("a"), c("a"), c("a"), c("a")), 3, 3, 2, 2), "3×3 配方放不进 2×2");
        assertEquals(2, CraftPlanner.batches(8, 4));
        assertEquals(1, CraftPlanner.batches(3, 4));
        assertEquals(3, CraftPlanner.batches(9, 4));
    }

    @Test
    void 脚边选位() {
        PlaceSpot.Cell feet = new PlaceSpot.Cell(10, 64, 10);
        // 东边被挡，南边空且地面实心
        Optional<PlaceSpot.Cell> s = PlaceSpot.pick(feet, cell -> !cell.equals(new PlaceSpot.Cell(11, 64, 10)), cell -> true);
        assertEquals(new PlaceSpot.Cell(10, 64, 11), s.orElseThrow());
        // 四周都是空但下面是悬空（不实心）→ 没位置
        assertTrue(PlaceSpot.pick(feet, cell -> true, cell -> false).isEmpty());
        assertEquals(4, PlaceSpot.candidates(feet).size());
    }
}
