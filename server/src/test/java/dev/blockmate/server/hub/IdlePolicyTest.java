package dev.blockmate.server.hub;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 18：空闲循环的节流和拒绝记忆，全部用假时间。 */
class IdlePolicyTest {

    private static final long S = 1000;
    private static final long MIN = 60 * S;

    @Test
    void 主人刚说完60秒内不想_之后每60秒想一次() {
        IdlePolicy p = new IdlePolicy(60 * S);
        long t0 = 1_000_000;
        p.ownerSpoke(t0);
        assertFalse(p.dueToThink(t0 + 30 * S), "离主人上一句不到 60 秒");
        assertTrue(p.dueToThink(t0 + 61 * S));
        p.thought(t0 + 61 * S);
        assertFalse(p.dueToThink(t0 + 90 * S), "刚想过");
        assertTrue(p.dueToThink(t0 + 122 * S));
        p.reminded(t0 + 122 * S);
        assertFalse(p.dueToThink(t0 + 150 * S), "刚主动说过");
        assertTrue(p.dueToThink(t0 + 183 * S));
    }

    @Test
    void 提醒同类5分钟一次() {
        IdlePolicy p = new IdlePolicy(60 * S);
        assertTrue(p.allowRemind("night", 0));
        assertFalse(p.allowRemind("night", 4 * MIN));
        assertTrue(p.allowRemind("hungry", 4 * MIN), "别的种类不受影响");
        assertTrue(p.allowRemind("night", 5 * MIN));
    }

    @Test
    void 被拒的建议10分钟不提_连拒3次安静_主人说话解除() {
        IdlePolicy p = new IdlePolicy(60 * S);
        long t = 0;
        p.proposed("去挖 9 个铁矿", new JsonArray(), "m1", t);
        assertTrue(p.pendingWithin(t + 60 * S).isPresent());
        assertTrue(p.pendingWithin(t + 3 * MIN).isEmpty(), "2 分钟后的'不'不算对它说的");
        assertFalse(p.rejected(t + 30 * S));
        assertFalse(p.allowPropose("去挖 9 个铁矿！", t + 5 * MIN), "同一个建议（忽略标点）10 分钟不提");
        assertTrue(p.allowPropose("去砍树", t + 5 * MIN));
        assertTrue(p.allowPropose("去挖 9 个铁矿", t + 11 * MIN));
        p.proposed("去砍树", new JsonArray(), "m2", t + 5 * MIN);
        assertFalse(p.rejected(t + 5 * MIN + 10 * S));
        p.proposed("回家", new JsonArray(), "m3", t + 10 * MIN);
        assertTrue(p.rejected(t + 10 * MIN + 10 * S), "第三次进安静模式");
        assertTrue(p.quiet());
        assertFalse(p.dueToThink(t + 30 * MIN), "安静模式不想");
        p.ownerSpoke(t + 30 * MIN);
        assertFalse(p.quiet(), "主人主动说一句就解除");
        assertEquals(0, p.consecutiveRejects());
        assertTrue(p.dueToThink(t + 31 * MIN + 1));
    }

    @Test
    void 接受清掉提议并归零连拒() {
        IdlePolicy p = new IdlePolicy(60 * S);
        p.proposed("a", new JsonArray(), "m1", 0);
        p.rejected(1);
        p.proposed("b", new JsonArray(), "m2", 2);
        assertEquals("b", p.accepted().goal());
        assertEquals(0, p.consecutiveRejects());
        assertTrue(p.pendingWithin(3).isEmpty());
        assertEquals("去挖9个铁矿", IdlePolicy.goalKey("去挖 9 个铁矿！"));
    }
}
