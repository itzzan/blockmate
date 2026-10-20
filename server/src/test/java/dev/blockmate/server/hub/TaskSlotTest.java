package dev.blockmate.server.hub;

import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Reason;
import dev.blockmate.server.Config;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 细则第 8 节的状态机。 */
class TaskSlotTest {

    private final TaskSlot slot = new TaskSlot(new Config.Timeouts(3, 90, 45, 90, 5, 60));

    @Test
    void 正常流程_pending_running_idle() {
        slot.start("c1", CommandName.COME_HERE, 0, "m1");
        assertEquals(TaskSlot.State.PENDING, slot.current().orElseThrow().state());
        assertEquals(TaskSlot.AckOutcome.STARTED, slot.onAck("c1", true));
        assertEquals(TaskSlot.State.RUNNING, slot.current().orElseThrow().state());
        assertEquals(TaskSlot.ResultOutcome.FINISHED, slot.onResult("c1"));
        assertTrue(slot.current().isEmpty());
    }

    @Test
    void 三秒无ack合成executor_unresponsive() {
        slot.start("c1", CommandName.FOLLOW, 0, "m1");
        assertTrue(slot.check(2999).isEmpty());
        List<TaskSlot.Synth> s = slot.check(3001);
        assertEquals(1, s.size());
        assertEquals(Reason.EXECUTOR_UNRESPONSIVE, s.get(0).reason());
        assertEquals("m1", s.get(0).sourceMessageId());
        assertTrue(slot.current().isEmpty());
    }

    @Test
    void 超时按命令不同_follow不超时() {
        slot.start("c1", CommandName.COME_HERE, 0, null);
        slot.onAck("c1", true);
        assertTrue(slot.check(89_000).isEmpty());
        assertEquals(Reason.TIMEOUT, slot.check(90_001).get(0).reason());

        slot.start("c2", CommandName.FOLLOW, 0, null);
        slot.onAck("c2", true);
        assertTrue(slot.check(3_600_000).isEmpty(), "follow 永不超时");
        assertTrue(slot.current().isPresent());
    }

    @Test
    void 新任务替换旧任务_旧任务的cancelled要认() {
        slot.start("c1", CommandName.FOLLOW, 0, "m1");
        slot.onAck("c1", true);
        assertEquals("c1", slot.start("c2", CommandName.COME_HERE, 100, "m2").orElseThrow().commandId());
        assertEquals("c2", slot.current().orElseThrow().commandId());
        assertEquals(TaskSlot.ResultOutcome.CANCELLED_OLD, slot.onResult("c1"));
        assertEquals(TaskSlot.AckOutcome.STARTED, slot.onAck("c2", true));
        assertEquals(TaskSlot.ResultOutcome.FINISHED, slot.onResult("c2"));
    }

    @Test
    void 旧任务的cancelled晚于新任务的ack也要认() {
        slot.start("c1", CommandName.FOLLOW, 0, null);
        slot.onAck("c1", true);
        slot.start("c2", CommandName.COME_HERE, 100, null);
        slot.onAck("c2", true);
        assertEquals(TaskSlot.ResultOutcome.CANCELLED_OLD, slot.onResult("c1"));
        assertEquals("c2", slot.current().orElseThrow().commandId());
    }

    @Test
    void stop不占槽_手头任务转为等cancelled() {
        slot.start("c1", CommandName.FOLLOW, 0, null);
        slot.onAck("c1", true);
        assertEquals("c1", slot.onStopIssued(50).orElseThrow().commandId());
        assertTrue(slot.current().isEmpty());
        assertEquals(TaskSlot.ResultOutcome.CANCELLED_OLD, slot.onResult("c1"));
    }

    @Test
    void 被拒绝的ack清空槽位() {
        slot.start("c1", CommandName.DELIVER, 0, null);
        assertEquals(TaskSlot.AckOutcome.REJECTED, slot.onAck("c1", false));
        assertTrue(slot.current().isEmpty());
        assertEquals(TaskSlot.AckOutcome.UNKNOWN, slot.onAck("c9", true));
    }

    @Test
    void mc断线合成disconnected() {
        slot.start("c1", CommandName.PICK_UP, 0, "m1");
        slot.onAck("c1", true);
        TaskSlot.Synth s = slot.onExecutorLost().orElseThrow();
        assertEquals(Reason.DISCONNECTED, s.reason());
        assertTrue(slot.current().isEmpty());
        assertTrue(slot.onExecutorLost().isEmpty());
    }

    @Test
    void 等cancelled超过十秒就丢掉() {
        slot.start("c1", CommandName.FOLLOW, 0, null);
        slot.start("c2", CommandName.FOLLOW, 100, null);
        slot.onAck("c2", true);
        assertEquals(1, slot.awaitingCancelCount());
        slot.check(10_200);
        assertEquals(0, slot.awaitingCancelCount());
        assertEquals(TaskSlot.ResultOutcome.UNKNOWN, slot.onResult("c1"));
        assertFalse(slot.current().isEmpty());
    }
}
