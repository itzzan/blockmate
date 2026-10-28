package dev.blockmate.server.hub;

import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Reason;
import dev.blockmate.server.Config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * hub 侧的单任务槽状态机（细则第 8 节），纯逻辑、可测试。
 * idle → pending（已发 command，等 ack）→ running（ack accepted）→ idle（result）。
 * 3 秒无 ack → executor_unresponsive；超过时长 → timeout；mc 断线 → disconnected。这三种由 hub 自己合成 result。
 */
public final class TaskSlot {

    public enum State { PENDING, RUNNING }

    public record Task(String commandId, CommandName name, State state, long createdAt, String sourceMessageId) {
        Task running() {
            return new Task(commandId, name, State.RUNNING, createdAt, sourceMessageId);
        }
    }

    /** hub 自己合成的失败结果。 */
    public record Synth(String commandId, CommandName name, Reason reason, String sourceMessageId) {
    }

    public enum AckOutcome { STARTED, REJECTED, UNKNOWN }

    public enum ResultOutcome { FINISHED, CANCELLED_OLD, UNKNOWN }

    private static final long AWAITING_CANCEL_MAX_MS = 10_000;

    private final Config.Timeouts timeouts;
    private Task current;
    /** 被新任务替换、等它自己的 cancelled 到来的旧任务；超过 10 秒没来就悄悄丢掉。 */
    private final Map<String, Long> awaitingCancel = new LinkedHashMap<>();

    public TaskSlot(Config.Timeouts timeouts) {
        this.timeouts = timeouts;
    }

    public Optional<Task> current() {
        return Optional.ofNullable(current);
    }

    /** 新 command 已发出。返回被替换掉的旧任务（如果有）。 */
    public Optional<Task> start(String commandId, CommandName name, long now, String sourceMessageId) {
        Task old = current;
        if (old != null) {
            awaitingCancel.put(old.commandId(), now);
        }
        current = new Task(commandId, name, State.PENDING, now, sourceMessageId);
        return Optional.ofNullable(old);
    }

    /** 发了 stop / cancel：手头任务不再占槽，等它的 cancelled。 */
    public Optional<Task> onStopIssued(long now) {
        Task old = current;
        if (old != null) {
            awaitingCancel.put(old.commandId(), now);
            current = null;
        }
        return Optional.ofNullable(old);
    }

    public AckOutcome onAck(String commandId, boolean accepted) {
        if (current == null || !current.commandId().equals(commandId)) {
            return AckOutcome.UNKNOWN;
        }
        if (accepted) {
            current = current.running();
            return AckOutcome.STARTED;
        }
        current = null;
        return AckOutcome.REJECTED;
    }

    public ResultOutcome onResult(String commandId) {
        if (current != null && current.commandId().equals(commandId)) {
            current = null;
            return ResultOutcome.FINISHED;
        }
        if (awaitingCancel.remove(commandId) != null) {
            return ResultOutcome.CANCELLED_OLD;
        }
        return ResultOutcome.UNKNOWN;
    }

    /** 定时检查：返回需要 hub 合成的失败结果。 */
    public List<Synth> check(long now) {
        List<Synth> out = new ArrayList<>();
        if (current != null) {
            long age = now - current.createdAt();
            if (current.state() == State.PENDING && age > timeouts.ackS() * 1000L) {
                out.add(new Synth(current.commandId(), current.name(), Reason.EXECUTOR_UNRESPONSIVE,
                        current.sourceMessageId()));
                current = null;
            } else if (current.state() == State.RUNNING) {
                long limit = timeoutMs(current.name());
                if (limit > 0 && age > limit) {
                    out.add(new Synth(current.commandId(), current.name(), Reason.TIMEOUT, current.sourceMessageId()));
                    current = null;
                }
            }
        }
        Iterator<Map.Entry<String, Long>> it = awaitingCancel.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > AWAITING_CANCEL_MAX_MS) {
                it.remove();
            }
        }
        return out;
    }

    /** mc 断线：手头任务按 disconnected 结束。 */
    public Optional<Synth> onExecutorLost() {
        awaitingCancel.clear();
        if (current == null) {
            return Optional.empty();
        }
        Task t = current;
        current = null;
        return Optional.of(new Synth(t.commandId(), t.name(), Reason.DISCONNECTED, t.sourceMessageId()));
    }

    /** 各命令的超时毫秒数；0 表示不超时（follow、wait_here）。 */
    public long timeoutMs(CommandName name) {
        return switch (name) {
            case COME_HERE -> timeouts.comeHereS() * 1000L;
            case PICK_UP -> timeouts.pickUpS() * 1000L;
            case DELIVER -> timeouts.deliverS() * 1000L;
            case STOP -> timeouts.stopS() * 1000L;
            case REJOIN -> timeouts.rejoinS() * 1000L;
            case REPORT_STATUS, REMEMBER_PLACE -> 5000L;
            case FOLLOW, WAIT_HERE, CANCEL -> 0L;
            // 任务单 9 加的命令：先写死默认值，任务单 10 做队列时搬进 config（handbook 第 8 节）
            case GOTO, GO_HOME -> 120_000L;
            case MINE, USE_FURNACE -> 300_000L;
            case COLLECT -> 45_000L;
            case CRAFT -> 30_000L;
            case PLACE, PICKUP_BLOCK -> 20_000L;
            case EAT -> 15_000L;
            case FIGHT -> timeouts.fightS() * 1000L; // 任务单 20
            case LEAD -> 0L; // 任务单 21：像 follow 一样不超时，危险停下 2 分钟由模组自己按 stuck 结束
            case CONTINUE -> 5000L;
            // 任务单 22
            case BUILD_PORTAL -> 180_000L;
            case LIGHT_PORTAL -> 30_000L;
            case ENTER_PORTAL -> 60_000L;
            case EXPLORE -> 330_000L;
            case ASK_OWNER -> 0L; // hub 自己处理，不下发
            case SLEEP -> 90_000L;
        };
    }

    public int awaitingCancelCount() {
        return awaitingCancel.size();
    }
}
