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
    private final List<PlanStepState> states;
    private final List<Result> results;
    private final long startedAt;
    private int current = -1;
    /** 整个计划的重试次数（任务单 11：最多 2 次）。 */
    private int retries;
    public static final int MAX_RETRIES = 2;
    private Outcome outcome = Outcome.RUNNING;
    private long endedAt = -1;

    public PlanRun(String planId, String goal, List<Step> steps, String sourceMessageId, long now) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("计划至少要有一步");
        }
        this.planId = planId;
        this.goal = goal;
        this.sourceMessageId = sourceMessageId;
        this.steps = new ArrayList<>(steps);
        this.states = new ArrayList<>();
        this.results = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            states.add(PlanStepState.PENDING);
            results.add(null);
        }
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
        return List.copyOf(steps);
    }

    public PlanStepState state(int index) {
        return states.get(index);
    }

    public Result result(int index) {
        return results.get(index);
    }

    /** 正在做的那一步；没有（刚建好或已结束）为空。 */
    public Optional<Step> currentStep() {
        return current >= 0 && current < steps.size() && states.get(current) == PlanStepState.RUNNING
                ? Optional.of(steps.get(current)) : Optional.empty();
    }

    /** 把下一步（第一个 pending）标成 running 并返回；没有了就返回空（调用方据此结束计划）。 */
    public Optional<Step> advance() {
        for (int i = Math.max(0, current); i < steps.size(); i++) {
            if (states.get(i) == PlanStepState.PENDING) {
                current = i;
                states.set(i, PlanStepState.RUNNING);
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
        states.set(current, PlanStepState.DONE);
        results.set(current, r);
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
            states.set(current, PlanStepState.FAILED);
            results.set(current, r);
        }
    }

    /** 失败处理之一：跳过这一步，接着做下一步（调用方随后 advance）。 */
    public void skipCurrent() {
        if (current >= 0) {
            states.set(current, PlanStepState.SKIPPED);
        }
    }

    /** 失败处理之二：这一步再试一次；整个计划最多 MAX_RETRIES 次。返回 false 表示额度用完。 */
    public boolean retryCurrent() {
        if (current < 0 || retries >= MAX_RETRIES) {
            return false;
        }
        retries++;
        states.set(current, PlanStepState.RUNNING);
        return true;
    }

    public int retries() {
        return retries;
    }

    /** 失败处理之三（任务单 11）：当前失败步之后的剩余步换成 newSteps（index 接着编）。 */
    public void replan(List<Step> newSteps) {
        if (current < 0) {
            return;
        }
        while (steps.size() > current + 1) {
            steps.remove(steps.size() - 1);
            states.remove(states.size() - 1);
            results.remove(results.size() - 1);
        }
        int idx = current + 1;
        for (Step ns : newSteps) {
            steps.add(new Step(idx++, ns.command(), ns.args()));
            states.add(PlanStepState.PENDING);
            results.add(null);
        }
    }

    /** 失败处理之三 / stop / 超时：剩下没做的全部 skipped，计划结束。 */
    public void abort(Outcome how, long now) {
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i) == PlanStepState.PENDING || states.get(i) == PlanStepState.RUNNING) {
                states.set(i, PlanStepState.SKIPPED);
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
            out.add(new Plan.Step(s.index(), s.command(), s.args() == null ? new JsonObject() : s.args(), states.get(i)));
        }
        Integer cur = running() && current >= 0 && states.get(current) == PlanStepState.RUNNING ? current : null;
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
            o.addProperty("state", states.get(i).wire());
            if (results.get(i) != null && results.get(i).reason() != null) {
                o.addProperty("reason", results.get(i).reason().wire());
            }
            arr.add(o);
        }
        return arr.toString();
    }
}
