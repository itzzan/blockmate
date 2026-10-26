package dev.blockmate.server.hub;

import com.google.gson.JsonObject;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Plan;
import dev.blockmate.protocol.PlanStepState;
import dev.blockmate.protocol.Reason;
import dev.blockmate.protocol.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计划的逐步推进、停止、失败处理（任务单 10）。 */
class PlanRunTest {

    private static PlanRun plan() {
        return new PlanRun("p1", "去弄木头", List.of(
                new PlanRun.Step(0, CommandName.COME_HERE, new JsonObject()),
                new PlanRun.Step(1, CommandName.PICK_UP, new JsonObject()),
                new PlanRun.Step(2, CommandName.DELIVER, new JsonObject())), "m1", 1000);
    }

    @Test
    void 三步顺序做完() {
        PlanRun p = plan();
        assertEquals(CommandName.COME_HERE, p.advance().orElseThrow().command());
        assertEquals(0, p.toProtocol().current());
        assertFalse(p.stepDone(Result.ok("c0")));
        assertEquals(CommandName.PICK_UP, p.advance().orElseThrow().command());
        assertFalse(p.stepDone(Result.ok("c1")));
        assertEquals(CommandName.DELIVER, p.advance().orElseThrow().command());
        assertTrue(p.stepDone(Result.ok("c2")), "最后一步做完计划结束");
        assertEquals(PlanRun.Outcome.DONE, p.outcome());
        assertTrue(p.advance().isEmpty());
        Plan msg = p.toProtocol();
        assertNull(msg.current());
        assertTrue(msg.steps().stream().allMatch(s -> s.state() == PlanStepState.DONE));
    }

    @Test
    void 中途停止_剩余跳过() {
        PlanRun p = plan();
        p.advance();
        p.stepDone(Result.ok("c0"));
        p.advance();
        p.abort(PlanRun.Outcome.ABORTED, 5000);
        assertEquals(PlanRun.Outcome.ABORTED, p.outcome());
        assertEquals(List.of(PlanStepState.DONE, PlanStepState.SKIPPED, PlanStepState.SKIPPED),
                p.toProtocol().steps().stream().map(Plan.Step::state).toList());
        assertEquals(5000, p.endedAt());
        assertTrue(p.advance().isEmpty(), "结束后不再派步");
    }

    @Test
    void 失败后跳过或重试或放弃() {
        PlanRun p = plan();
        p.advance();
        p.stepFailed(Result.failed("c0", Reason.STUCK));
        assertTrue(p.retryCurrent(), "第一次允许重试");
        assertEquals(PlanStepState.RUNNING, p.state(0));
        p.stepFailed(Result.failed("c0b", Reason.STUCK));
        assertTrue(p.retryCurrent(), "整个计划最多重试 2 次");
        p.stepFailed(Result.failed("c0c", Reason.STUCK));
        assertFalse(p.retryCurrent(), "第 3 次不行");
        p.skipCurrent();
        assertEquals(PlanStepState.SKIPPED, p.state(0));
        assertEquals(CommandName.PICK_UP, p.advance().orElseThrow().command());
        p.stepFailed(Result.failed("c1", Reason.ITEM_NOT_FOUND));
        p.abort(PlanRun.Outcome.FAILED, 9000);
        assertEquals(PlanRun.Outcome.FAILED, p.outcome());
        assertEquals(PlanStepState.FAILED, p.state(1));
        assertEquals(PlanStepState.SKIPPED, p.state(2));
        assertTrue(p.stepsJson().contains("\"reason\":\"item_not_found\""), p.stepsJson());
    }

    @Test
    void 重排剩余步骤() {
        PlanRun p = plan();
        p.advance();
        p.stepDone(Result.ok("c0"));
        p.advance();
        p.stepFailed(Result.failed("c1", Reason.ITEM_NOT_FOUND));
        p.replan(List.of(new PlanRun.Step(0, CommandName.MINE, new JsonObject()),
                new PlanRun.Step(0, CommandName.DELIVER, new JsonObject())));
        assertEquals(4, p.size(), "失败步保留，后面换成两步新的");
        assertEquals(CommandName.MINE, p.steps().get(2).command());
        assertEquals(2, p.steps().get(2).index());
        assertEquals(3, p.steps().get(3).index());
        assertEquals(PlanStepState.FAILED, p.state(1));
        assertEquals(CommandName.MINE, p.advance().orElseThrow().command());
    }
}
