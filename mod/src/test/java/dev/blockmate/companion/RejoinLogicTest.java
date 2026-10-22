package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖游戏的重连 / 自动重生逻辑（2026-10-22 补丁）。 */
class RejoinLogicTest {

    @Test
    void 掉线按间隔重连_最多五次_进服清零() {
        RejoinPolicy p = new RejoinPolicy(10_000, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(p.onDisconnected(0), "第 " + (i + 1) + " 次应安排重连");
            assertFalse(p.due(9_999));
            assertTrue(p.due(10_000));
            assertEquals(i + 1, p.startAttempt());
        }
        assertFalse(p.onDisconnected(0), "第 6 次不再重连");
        p.onJoined();
        assertEquals(0, p.attempts());
        assertTrue(p.onDisconnected(0));
    }

    @Test
    void 被服务器明确拒绝的原因不重连() {
        assertTrue(RejoinPolicy.isPermanent("multiplayer.disconnect.not_whitelisted"));
        assertTrue(RejoinPolicy.isPermanent("multiplayer.disconnect.banned.reason"));
        assertTrue(RejoinPolicy.isPermanent("multiplayer.disconnect.outdated_client"));
        assertFalse(RejoinPolicy.isPermanent("multiplayer.disconnect.kicked"), "被管理员踢，可以重连");
        assertFalse(RejoinPolicy.isPermanent("multiplayer.disconnect.server_full"));
        assertFalse(RejoinPolicy.isPermanent("disconnect.lost"));
        assertFalse(RejoinPolicy.isPermanent(null), "拿不到原因就当可重试");
    }

    @Test
    void 取消后不再到期_进服后恢复() {
        RejoinPolicy p = new RejoinPolicy(10_000, 5);
        assertTrue(p.onDisconnected(0));
        p.cancel();
        assertFalse(p.due(20_000));
        assertFalse(p.pending());
        assertFalse(p.onDisconnected(30_000), "取消等于用完次数");
        p.onJoined();
        assertTrue(p.onDisconnected(40_000));
    }

    @Test
    void 死亡画面停留三秒才自动重生_消失就重置_一直在就每三秒再按() {
        AutoRespawn a = new AutoRespawn(3_000);
        assertFalse(a.tick(false, 0));
        assertFalse(a.tick(true, 1_000), "刚出现，开始计时");
        assertFalse(a.tick(true, 3_999));
        assertTrue(a.tick(true, 4_000), "满 3 秒按一次");
        assertFalse(a.tick(true, 4_050), "刚按过，等");
        assertTrue(a.tick(true, 7_000), "画面还在，再按");
        assertFalse(a.tick(false, 7_100), "画面没了");
        assertFalse(a.tick(true, 8_000), "重新出现，重新计时");
        assertFalse(a.tick(true, 10_999));
        assertTrue(a.tick(true, 11_000));
    }
}
