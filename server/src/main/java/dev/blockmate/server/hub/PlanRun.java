package dev.blockmate.server.hub;

import com.google.gson.JsonObject;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Plan;
import dev.blockmate.protocol.PlanStepState;
import dev.blockmate.protocol.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一个计划的执行状态（任务单 10，guide-2 第 1.2 节）：若干步按顺序派给模组，一步完成再派下一步。纯逻辑，hub 线程上用。
 * 单个动作就是只有一步的计划。
 */
public final class PlanRun {

    public enum Outcome { RUNNING, DONE, FAILED, ABORTED, TIMEOUT }

    public record Step(int index, CommandName command, JsonObject args) {
    }

    private final String planId;
    private final String goal;
    private final String sourceMessageId;
    private final List<Step> steps;
    private final PlanStepState[] states;
    private final Result[] results;
    private final long startedAt;
    private int current = -1;
    private int retries;
    private Outcome outcome = Outcome.RUNNING;
    private long endedAt = -1;

    public PlanRun(String planId, String goal, List<Step> steps, String sourceMessageId, long now) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("计划至少要有一步");
        }
        this.planId = planId;
        this.goal = goal;
        this.sourceMessageId = sourceMessageId;
        this.steps = List.copyOf(steps);
        this.states = new PlanStepState[steps.size()];
        this.results = new Result[steps.size()];
        java.util.Arrays.fill(states, PlanStepState.PENDING);
        this.startedAt = now;
    }

    public String planId() {
        return planId;
    }

    public String goal() {
        return goal;
    }

    public String sourceMessageId() {
        return sourceMessageId;
    }

    public long startedAt() {
        return startedAt;
    }

    public long endedAt() {
        return endedAt;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean running() {
        return outcome == Outcome.RUNNING;
    }

    public int size() {
        return steps.size();
    }

    public List<Step> steps() {
        return steps;
    }

    public PlanStepState state(int index) {
        return states[index];
    }

    public Result result(int index) {
        return results[index];
    }

    /** 正在做的那一步；没有（刚建好或已结束）为空。 */
    public Optional<Step> currentStep() {
        return current >= 0 && current < steps.size() && states[current] == PlanStepState.RUNNING
                ? Optional.of(steps.get(current)) : Optional.empty();
    }

    /** 把下一步（第一个 pending）标成 running 并返回；没有了就返回空（调用方据此结束计划）。 */
    public Optional<Step> advance() {
        for (int i = Math.max(0, current); i < steps.size(); i++) {
            if (states[i] == PlanStepState.PENDING) {
                current = i;
                states[i] = PlanStepState.RUNNING;
                retries = 0;
                return Optional.of(steps.get(i));
            }
        }
        return Optional.empty();
    }

    /** 当前步做成了。返回 true 表示计划全部做完。 */
    public boolean stepDone(Result r) {
        if (current < 0) {
            return false;
        }
        states[current] = PlanStepState.DONE;
        results[current] = r;
        boolean allDone = true;
        for (PlanStepState s : states) {
            if (s == PlanStepState.PENDING || s == PlanStepState.RUNNING) {
                allDone = false;
                break;
            }
        }
        if (allDone) {
            end(Outcome.DONE, 0);
        }
        return allDone;
    }

    /** 当前步失败了，先记下来，怎么办由调用方（问脑子）决定。 */
    public void stepFailed(Result r) {
        if (current >= 0) {
            states[current] = PlanStepState.FAILED;
            results[current] = r;
        }
    }

    /** 失败处理之一：跳过这一步，接着做下一步（调用方随后 advance）。 */
    public void skipCurrent() {
        if (current >= 0) {
            states[current] = PlanStepState.SKIPPED;
        }
    }

    /** 失败处理之二：再试一次（最多一次）。返回 false 表示已经试过了。 */
    public boolean retryCurrent() {
        if (current < 0 || retries >= 1) {
            return false;
        }
        retries++;
        states[current] = PlanStepState.RUNNING;
        return true;
    }

    /** 失败处理之三 / stop / 超时：剩下没做的全部 skipped，计划结束。 */
    public void abort(Outcome how, long now) {
        for (int i = 0; i < states.length; i++) {
            if (states[i] == PlanStepState.PENDING || states[i] == PlanStepState.RUNNING) {
                states[i] = PlanStepState.SKIPPED;
            }
        }
        end(how, now);
    }

    private void end(Outcome how, long now) {
        if (outcome == Outcome.RUNNING) {
            outcome = how;
            endedAt = now;
        }
    }

    /** 给网页的 plan 消息。 */
    public Plan toProtocol() {
        List<Plan.Step> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            out.add(new Plan.Step(s.index(), s.command(), s.args() == null ? new JsonObject() : s.args(), states[i]));
        }
        Integer cur = running() && current >= 0 && states[current] == PlanStepState.RUNNING ? current : null;
        return new Plan(planId, goal, out, cur);
    }

    /** 存进 plans 表的步骤 JSON（命令、参数、状态、结果原因）。 */
    public String stepsJson() {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (int i = 0; i < steps.size(); i++) {
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("command", steps.get(i).command().wire());
            o.add("args", steps.get(i).args() == null ? new JsonObject() : steps.get(i).args());
            o.addProperty("state", states[i].wire());
            if (results[i] != null && results[i].reason() != null) {
                o.addProperty("reason", results[i].reason().wire());
            }
            arr.add(o);
        }
        return arr.toString();
    }
}
