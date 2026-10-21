package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖游戏的 observer 逻辑：背包比对、聊天过滤。 */
class ObserverLogicTest {

    @Test
    void 背包变化_捡到为正_丢出为负_新物品和消失() {
        Map<String, Integer> prev = Map.of("minecraft:oak_log", 8, "minecraft:bread", 3);
        Map<String, Integer> now = Map.of("minecraft:oak_log", 16, "minecraft:apple", 1);
        List<Inventories.Change> d = Inventories.diff(prev, now);
        assertEquals(3, d.size());
        assertEquals(new Inventories.Change("minecraft:apple", 1, 1), d.get(0));
        assertEquals(new Inventories.Change("minecraft:bread", -3, 0), d.get(1));
        assertEquals(new Inventories.Change("minecraft:oak_log", 8, 16), d.get(2));
        assertTrue(Inventories.diff(now, now).isEmpty());
    }

    @Test
    void 背包累加忽略空() {
        Map<String, Integer> m = Inventories.empty();
        Inventories.add(m, "minecraft:oak_log", 5);
        Inventories.add(m, "minecraft:oak_log", 3);
        Inventories.add(m, null, 3);
        Inventories.add(m, "minecraft:air", 0);
        assertEquals(Map.of("minecraft:oak_log", 8), m);
    }

    @Test
    void 只认主人的聊天() {
        assertEquals("跟我来", ChatFilter.ownerTextFromSystem("<Steve> 跟我来", "Steve").orElseThrow());
        assertTrue(ChatFilter.ownerTextFromSystem("<Alex> 跟我来", "Steve").isEmpty());
        assertTrue(ChatFilter.ownerTextFromSystem("<Steve>跟我来", "Steve").isEmpty(), "原版格式带空格");
        assertTrue(ChatFilter.ownerTextFromSystem("Steve joined the game", "Steve").isEmpty());
        assertTrue(ChatFilter.isOwner("Steve", "Steve"));
        assertFalse(ChatFilter.isOwner("Alex", "Steve"));
        assertFalse(ChatFilter.isOwner(null, "Steve"));
    }
}
