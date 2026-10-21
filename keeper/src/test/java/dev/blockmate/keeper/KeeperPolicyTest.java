package dev.blockmate.keeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 7.2 第 2–4 条的决策。 */
class KeeperPolicyTest {

    @Test
    void 默认期望停止_在跑就关() {
        KeeperPolicy p = new KeeperPolicy(3, 60_000);
        assertEquals(KeeperPolicy.Action.NONE, p.decide(false, 0));
        assertEquals(KeeperPolicy.Action.STOP, p.decide(true, 0));
    }

    @Test
    void 期望在跑_不在就拉起_刚拉起要等() {
        KeeperPolicy p = new KeeperPolicy(3, 60_000);
        p.setDesired(KeeperPolicy.Desired.RUNNING);
        assertEquals(KeeperPolicy.Action.START, p.decide(false, 0));
        p.onStarted(0);
        assertEquals(KeeperPolicy.Action.NONE, p.decide(false, 30_000), "启动中，等");
        assertEquals(KeeperPolicy.Action.NONE, p.decide(true, 70_000), "起来了");
        assertEquals(KeeperPolicy.Action.START, p.decide(false, 80_000), "又死了，再拉");
    }

    @Test
    void 十分钟内最多三次_超过就放弃_再点上线重来() {
        KeeperPolicy p = new KeeperPolicy(3, 0);
        p.setDesired(KeeperPolicy.Desired.RUNNING);
        for (int i = 0; i < 3; i++) {
            assertEquals(KeeperPolicy.Action.START, p.decide(false, i * 1000L));
            p.onStarted(i * 1000L);
        }
        assertEquals(KeeperPolicy.Action.NONE, p.decide(false, 5000));
        assertTrue(p.gaveUp());
        assertEquals(KeeperPolicy.Action.NONE, p.decide(false, 6000), "放弃后不再试");

        p.setDesired(KeeperPolicy.Desired.STOPPED);
        p.setDesired(KeeperPolicy.Desired.RUNNING);
        assertFalse(p.gaveUp());
        assertEquals(KeeperPolicy.Action.START, p.decide(false, 7000));
    }

    @Test
    void 窗口滑动_十分钟后预算恢复() {
        KeeperPolicy p = new KeeperPolicy(3, 0);
        p.setDesired(KeeperPolicy.Desired.RUNNING);
        for (int i = 0; i < 3; i++) {
            p.decide(false, i * 1000L);
            p.onStarted(i * 1000L);
        }
        assertEquals(KeeperPolicy.Action.NONE, p.decide(false, 4000));
        // gaveUp 后即便过了十分钟也不自动重来，要用户再点一次（手册：超过就停止尝试并报告）
        assertEquals(KeeperPolicy.Action.NONE, p.decide(false, 11 * 60_000L));
        KeeperPolicy q = new KeeperPolicy(3, 0);
        q.setDesired(KeeperPolicy.Desired.RUNNING);
        q.decide(false, 0);
        q.onStarted(0);
        q.decide(false, 1000);
        q.onStarted(1000);
        assertEquals(1, q.restartsInWindow(10 * 60_000L + 500), "第一笔已滑出窗口");
    }

    @Test
    void 期望停止时不在跑什么都不做() {
        KeeperPolicy p = new KeeperPolicy(3, 60_000);
        p.setDesired(KeeperPolicy.Desired.RUNNING);
        p.setDesired(KeeperPolicy.Desired.STOPPED);
        assertEquals(KeeperPolicy.Action.NONE, p.decide(false, 0));
    }
}
