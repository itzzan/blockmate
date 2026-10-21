package dev.blockmate.companion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 6.4 第 5–7 条里不依赖游戏的逻辑：丢物品计划、捡起确认、重连次数。 */
class DeliveryLogicTest {

    @BeforeEach
    void clean() {
        DeliveryWatch.clear();
    }

    @Test
    void 槽位换算_快捷栏在36到44() {
        assertEquals(36, ThrowPlan.slotFor(0));
        assertEquals(44, ThrowPlan.slotFor(8));
        assertEquals(9, ThrowPlan.slotFor(9));
        assertEquals(35, ThrowPlan.slotFor(35));
    }

    @Test
    void 整组整丢_零头逐个丢() {
        ThrowPlan.Plan p = ThrowPlan.plan(List.of(new ThrowPlan.Stack(0, 64), new ThrowPlan.Stack(9, 10)), 67);
        assertEquals(67, p.total());
        assertEquals(4, p.clicks().size());
        assertEquals(new ThrowPlan.Click(36, 1), p.clicks().get(0));
        assertEquals(new ThrowPlan.Click(9, 0), p.clicks().get(1));
        assertEquals(new ThrowPlan.Click(9, 0), p.clicks().get(3));
    }

    @Test
    void 没那么多就丢全部() {
        ThrowPlan.Plan p = ThrowPlan.plan(List.of(new ThrowPlan.Stack(3, 5)), 20);
        assertEquals(5, p.total());
        assertEquals(List.of(new ThrowPlan.Click(39, 1)), p.clicks());
        assertEquals(0, ThrowPlan.plan(List.of(), 8).total());
    }

    @Test
    void 捡起确认_看玩家_物品_时间() {
        DeliveryWatch.record(new DeliveryWatch.Take(7, 100, "minecraft:oak_log", 8, 1000));
        assertTrue(DeliveryWatch.confirmedSince(100, "minecraft:oak_log", 900));
        assertFalse(DeliveryWatch.confirmedSince(100, "minecraft:oak_log", 1001), "丢之前的记录不算");
        assertFalse(DeliveryWatch.confirmedSince(101, "minecraft:oak_log", 900), "别人捡的不算");
        assertFalse(DeliveryWatch.confirmedSince(100, "minecraft:bread", 900), "别的物品不算");
        DeliveryWatch.record(new DeliveryWatch.Take(8, 100, null, 1, 2000));
        assertTrue(DeliveryWatch.confirmedSince(100, "minecraft:bread", 1500), "种类未知时按捡起者算");
    }

    @Test
    void 同一个包跑两次只记一次() {
        DeliveryWatch.record(new DeliveryWatch.Take(9, 100, "minecraft:dirt", 3, 5000));
        DeliveryWatch.record(new DeliveryWatch.Take(9, 100, "minecraft:dirt", 3, 5030));
        DeliveryWatch.record(new DeliveryWatch.Take(9, 100, "minecraft:dirt", 3, 5200));
        assertEquals(2, DeliveryWatch.size(), "30 ms 内的重复丢掉，200 ms 后的算新的");
    }

    @Test
    void 重连_十秒后_最多五次_进服清零() {
        RejoinPolicy r = new RejoinPolicy(10_000, 5);
        assertTrue(r.onDisconnected(0));
        assertFalse(r.due(9_999));
        assertTrue(r.due(10_000));
        assertEquals(1, r.startAttempt());
        assertFalse(r.pending());
        for (int i = 2; i <= 5; i++) {
            assertTrue(r.onDisconnected(0));
            assertEquals(i, r.startAttempt());
        }
        assertFalse(r.onDisconnected(0), "第六次不再重连");
        r.onJoined();
        assertEquals(0, r.attempts());
        assertTrue(r.onDisconnected(0));
        r.scheduleIn(0, 2000);
        assertTrue(r.due(2000));
    }
}
