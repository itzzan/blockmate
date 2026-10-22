package dev.blockmate.server.hub;

import dev.blockmate.server.gateway.RconClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static dev.blockmate.server.hub.PresencePolicy.Action.NONE;
import static dev.blockmate.server.hub.PresencePolicy.Action.START;
import static dev.blockmate.server.hub.PresencePolicy.Action.STOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 自动上下线的决策和 list 回复解析（手册 7.2 可选项）。 */
class PresencePolicyTest {

    private static final long TEN_MIN = 10 * 60_000L;

    @Test
    void 主人进服那一刻上线_在服期间不重复() {
        PresencePolicy p = new PresencePolicy(TEN_MIN);
        assertEquals(NONE, p.observe(false, false, 0), "一开始没人");
        assertEquals(START, p.observe(true, false, 30_000), "进服了");
        assertEquals(NONE, p.observe(true, true, 60_000), "伙伴已期望在线");
        assertEquals(NONE, p.observe(true, false, 90_000), "主人手动让伙伴下线了，不要立刻又拉起");
        assertEquals(NONE, p.observe(false, false, 120_000));
        assertEquals(START, p.observe(true, false, 150_000), "重新进服才再拉起");
    }

    @Test
    void 开关打开时主人已在服_伙伴没在线_直接上线() {
        PresencePolicy p = new PresencePolicy(TEN_MIN);
        assertEquals(START, p.observe(true, false, 0));
        PresencePolicy q = new PresencePolicy(TEN_MIN);
        assertEquals(NONE, q.observe(true, true, 0), "伙伴已经在线就不动");
    }

    @Test
    void 离开满十分钟下线一次_手动再上线不再下线_回来后重新计() {
        PresencePolicy p = new PresencePolicy(TEN_MIN);
        p.observe(true, true, 0);
        assertEquals(NONE, p.observe(false, true, 60_000), "刚走");
        assertEquals(NONE, p.observe(false, true, 60_000 + TEN_MIN - 1));
        assertEquals(STOP, p.observe(false, true, 60_000 + TEN_MIN), "满 10 分钟");
        assertEquals(NONE, p.observe(false, false, 60_000 + TEN_MIN + 30_000), "已经下线了");
        assertEquals(NONE, p.observe(false, true, 60_000 + 3 * TEN_MIN), "主人手动又叫它上线，这次离开不再下线");
        p.observe(true, true, 60_000 + 4 * TEN_MIN);
        p.observe(false, true, 60_000 + 5 * TEN_MIN);
        assertEquals(STOP, p.observe(false, true, 60_000 + 6 * TEN_MIN), "回来过一次后，下次离开满 10 分钟再下线");
    }

    @Test
    void 离线时间从首次观察算_重置后重新观察() {
        PresencePolicy p = new PresencePolicy(TEN_MIN);
        assertEquals(NONE, p.observe(false, true, 1_000_000), "首次观察就不在服，开始计时");
        assertEquals(STOP, p.observe(false, true, 1_000_000 + TEN_MIN));
        p.reset();
        assertEquals(NONE, p.observe(false, true, 2_000_000), "重置后重新计时");
    }

    @Test
    void 解析list回复() throws IOException {
        assertEquals(List.of("Blockmate"), RconClient.parseList("There are 1 of a max of 20 players online: Blockmate"));
        assertEquals(List.of("Zan421", "Blockmate"),
                RconClient.parseList("There are 2 of a max of 20 players online: Zan421, Blockmate"));
        assertEquals(List.of(), RconClient.parseList("There are 0 of a max of 20 players online: "));
        assertEquals(List.of(), RconClient.parseList("There are 0 of a max of 20 players online:"));
        assertThrows(IOException.class, () -> RconClient.parseList("Unknown command"));
        assertThrows(IOException.class, () -> RconClient.parseList(null));
    }
}
