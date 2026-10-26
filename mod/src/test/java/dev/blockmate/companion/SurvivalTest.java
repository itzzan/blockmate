package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 15：挑最差的食物、夜里判断。 */
class SurvivalTest {

    private static final Set<String> AVOID = Set.of("minecraft:rotten_flesh", "minecraft:spider_eye");

    @Test
    void 先吃差的_留好的() {
        List<Survival.Food> foods = List.of(
                new Survival.Food(3, "minecraft:cooked_beef", 8),
                new Survival.Food(5, "minecraft:bread", 5),
                new Survival.Food(7, "minecraft:apple", 4),
                new Survival.Food(9, "minecraft:rotten_flesh", 4));
        assertEquals("minecraft:apple", Survival.pick(foods, AVOID, null).orElseThrow().id(), "饱食度最低且不是腐肉");
        assertEquals("minecraft:bread", Survival.pick(foods, AVOID, "minecraft:bread").orElseThrow().id(), "指定了就吃指定的");
        assertTrue(Survival.pick(foods, AVOID, "minecraft:carrot").isEmpty(), "指定的没有");
    }

    @Test
    void 只剩腐肉才吃腐肉() {
        List<Survival.Food> only = List.of(new Survival.Food(1, "minecraft:rotten_flesh", 4), new Survival.Food(2, "minecraft:spider_eye", 2));
        assertEquals("minecraft:spider_eye", Survival.pick(only, AVOID, null).orElseThrow().id(), "都不该吃时仍挑饱食度最低的");
        assertTrue(Survival.pick(List.of(), AVOID, null).isEmpty());
    }

    @Test
    void 夜里判断和饿不饿() {
        assertFalse(Survival.isNight(0));
        assertFalse(Survival.isNight(12000));
        assertTrue(Survival.isNight(12542));
        assertTrue(Survival.isNight(18000));
        assertTrue(Survival.isNight(23460));
        assertFalse(Survival.isNight(23461));
        assertTrue(Survival.isNight(24000 * 3 + 15000), "第四天的半夜");
        assertTrue(Survival.hungry(14));
        assertFalse(Survival.hungry(15));
    }
}
