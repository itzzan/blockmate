package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 14 第二批：容器格子换算、燃料挑选。 */
class ContainerSlotsTest {

    @Test
    void 背包格在三种菜单里的位置() {
        for (int i = 0; i < 36; i++) {
            assertEquals(ThrowPlan.slotFor(i), ContainerSlots.slotFor(i, ContainerSlots.INVENTORY_MENU_START), "背包菜单和第一本手册的算法一致 i=" + i);
        }
        assertEquals(37, ContainerSlots.slotFor(0, ContainerSlots.CRAFTING_MENU_START), "工作台：快捷栏第 1 格");
        assertEquals(10, ContainerSlots.slotFor(9, ContainerSlots.CRAFTING_MENU_START), "工作台：主背包第 1 格");
        assertEquals(45, ContainerSlots.slotFor(8, ContainerSlots.CRAFTING_MENU_START));
        assertEquals(30, ContainerSlots.slotFor(0, ContainerSlots.FURNACE_MENU_START), "熔炉：快捷栏第 1 格");
        assertEquals(3, ContainerSlots.slotFor(9, ContainerSlots.FURNACE_MENU_START));
        assertEquals(29, ContainerSlots.slotFor(35, ContainerSlots.FURNACE_MENU_START));
    }

    @Test
    void 燃料挑选() {
        Map<String, Integer> inv = new LinkedHashMap<>();
        inv.put("minecraft:oak_log", 5);
        inv.put("minecraft:oak_planks", 4);
        inv.put("minecraft:coal", 2);
        assertEquals("minecraft:coal", FurnaceLoader.pickFuel(inv, null).orElseThrow(), "有煤先用煤");
        inv.remove("minecraft:coal");
        assertEquals("minecraft:oak_planks", FurnaceLoader.pickFuel(inv, null).orElseThrow(), "没煤用木板");
        inv.remove("minecraft:oak_planks");
        assertEquals("minecraft:oak_log", FurnaceLoader.pickFuel(inv, null).orElseThrow(), "再没有用原木");
        assertTrue(FurnaceLoader.pickFuel(Map.of("minecraft:raw_iron", 8), null).isEmpty(), "什么燃料都没有");
        assertTrue(FurnaceLoader.pickFuel(inv, "minecraft:coal").isEmpty(), "指定了煤但没有，不乱换");
        assertEquals("minecraft:oak_log", FurnaceLoader.pickFuel(inv, "minecraft:oak_log").orElseThrow());
    }
}
