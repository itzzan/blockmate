package dev.blockmate.mock;

import com.google.gson.JsonObject;
import dev.blockmate.protocol.Ack;
import dev.blockmate.protocol.Command;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Hello;
import dev.blockmate.protocol.HelloOk;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.OwnerInfo;
import dev.blockmate.protocol.Peer;
import dev.blockmate.protocol.Pos;
import dev.blockmate.protocol.Progress;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.Reason;
import dev.blockmate.protocol.Result;
import dev.blockmate.protocol.Snapshot;
import dev.blockmate.protocol.TaskInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 假执行器的全部逻辑（手册 5.4），不碰网络、不碰时钟：
 * 外面喂它 {@link #onConnected}、{@link #onMessage}、{@link #tick}，它通过 sink 发消息、通过 console 打印。
 * 单线程使用。
 */
public final class Simulator {

    /** 往后台发消息的出口。 */
    public interface Sink {
        void send(Envelope envelope);
    }

    private static final long PROGRESS_AFTER_MS = 1000;
    private static final long RESULT_AFTER_MS = 3000;

    private record Task(String commandId, CommandName name, long startedAt, boolean progressSent, boolean failStuck,
                        JsonObject args) {
        Task withProgressSent() {
            return new Task(commandId, name, startedAt, true, failStuck, args);
        }
    }

    private final Options opts;
    private final Sink sink;
    private final Consumer<String> console;

    private boolean connected;
    private long lastSnapshotAt;
    private double x = 100.0;
    private double y = 64.0;
    private double z = 100.0;
    private double heading = 0.0;
    private final Map<String, Integer> inventory = new LinkedHashMap<>();
    private Task task;
    private boolean failStuckPending;

    public Simulator(Options opts, Sink sink, Consumer<String> console) {
        this.opts = opts;
        this.sink = sink;
        this.console = console;
        this.inventory.put("minecraft:oak_log", 8);
        this.failStuckPending = opts.failStuck();
    }

    /** 连上后台：发 hello。snapshot 从一个间隔之后开始。 */
    public void onConnected(long now) {
        connected = true;
        lastSnapshotAt = now;
        send(MessageType.HELLO, Hello.mc(opts.token(), opts.player(), opts.owner(), "mock"));
    }

    /** 断线：丢掉手头的任务（细则第 15 节：重连后不补发 result）。 */
    public void onDisconnected() {
        connected = false;
        if (task != null) {
            console.accept("连接断开，丢弃任务 " + task.name().wire());
            task = null;
        }
    }

    public void onMessage(Envelope envelope, long now) {
        switch (envelope.type()) {
            case HELLO -> {
                HelloOk ok = ProtocolCodec.bodyAs(envelope, HelloOk.class);
                if (!ok.ok()) {
                    console.accept("后台拒绝了连接: " + ok.error());
                } else if (ok.rules() != null) {
                    console.accept("收到白名单 可挖=" + ok.rules().mineable().size() + " 可放=" + ok.rules().placeable().size());
                } else {
                    console.accept("hello 没带白名单");
                }
            }
            case COMMAND -> handleCommand(envelope, now);
            default -> console.accept("忽略 " + envelope.type().wire());
        }
    }

    /** 每隔一小会儿调一次（比如 200 ms），由它自己判断该不该发 snapshot / progress / result。 */
    public void tick(long now) {
        if (!connected) {
            return;
        }
        if (now - lastSnapshotAt >= opts.snapshotIntervalMs()) {
            lastSnapshotAt = now;
            drift();
            sendSnapshot();
        }
        if (task != null) {
            if (!task.progressSent() && now - task.startedAt() >= PROGRESS_AFTER_MS) {
                send(MessageType.PROGRESS, new Progress(task.commandId(), "pathing", 6.0, pos()));
                task = task.withProgressSent();
            }
            if (now - task.startedAt() >= RESULT_AFTER_MS) {
                finishTask();
            }
        }
    }

    private void handleCommand(Envelope envelope, long now) {
        Command cmd = ProtocolCodec.bodyAs(envelope, Command.class);
        if (cmd.name() == null) {
            console.accept("收到不认识的命令，回 rejected");
            if (!opts.silent()) {
                send(MessageType.ACK, Ack.rejected(envelope.id()));
            }
            return;
        }
        console.accept("收到 " + cmd.name().wire());
        if (opts.silent()) {
            return;
        }
        switch (cmd.name()) {
            case STOP -> {
                cancelCurrent();
                send(MessageType.ACK, Ack.accepted(envelope.id()));
                send(MessageType.RESULT, Result.ok(envelope.id(), new Result.StopDetail(true, 1)));
            }
            case CANCEL -> {
                Command.CancelArgs args = cmd.argsAs(Command.CancelArgs.class);
                send(MessageType.ACK, Ack.accepted(envelope.id()));
                if (task != null && (args == null || args.commandId() == null
                        || args.commandId().equals(task.commandId()))) {
                    cancelCurrent();
                }
                send(MessageType.RESULT, Result.ok(envelope.id()));
            }
            case REPORT_STATUS -> {
                send(MessageType.ACK, Ack.accepted(envelope.id()));
                sendSnapshot();
                send(MessageType.RESULT, Result.ok(envelope.id()));
            }
            default -> {
                cancelCurrent();
                task = new Task(envelope.id(), cmd.name(), now, false, failStuckPending, cmd.args());
                failStuckPending = false;
                send(MessageType.ACK, Ack.accepted(envelope.id()));
            }
        }
    }

    /** 新任务替换旧任务：旧任务先收到自己的 cancelled（手册 5.2 约束）。 */
    private void cancelCurrent() {
        if (task != null) {
            send(MessageType.RESULT, Result.cancelled(task.commandId()));
            task = null;
        }
    }

    private void finishTask() {
        Task t = task;
        task = null;
        if (t.failStuck()) {
            console.accept("按 --fail stuck 返回 stuck");
            send(MessageType.RESULT, Result.failed(t.commandId(), Reason.STUCK));
            return;
        }
        Object detail = switch (t.name()) {
            case DELIVER -> {
                Command.DeliverArgs a = ProtocolCodec.gson().fromJson(t.args(), Command.DeliverArgs.class);
                yield new Result.DeliverDetail(dev.blockmate.protocol.DeliveryOutcome.CONFIRMED,
                        a == null ? 0 : a.count());
            }
            case PICK_UP -> new Result.PickUpDetail(8);
            case REJOIN -> new Result.RejoinDetail(1);
            case GOTO -> new Result.GotoDetail(1.5);
            case MINE -> {
                Command.MineArgs a = ProtocolCodec.gson().fromJson(t.args(), Command.MineArgs.class);
                yield new Result.MineDetail(a == null ? 0 : a.count());
            }
            case COLLECT -> new Result.CollectDetail(3);
            case CRAFT -> {
                Command.CraftArgs a = ProtocolCodec.gson().fromJson(t.args(), Command.CraftArgs.class);
                yield new Result.CraftDetail(a == null ? 1 : a.countOrDefault());
            }
            case USE_FURNACE -> {
                Command.UseFurnaceArgs a = ProtocolCodec.gson().fromJson(t.args(), Command.UseFurnaceArgs.class);
                yield new Result.UseFurnaceDetail(a == null || a.count() == null ? 1 : a.count());
            }
            case PLACE, PICKUP_BLOCK -> new Result.PlaceDetail(pos());
            case EAT -> new Result.EatDetail("minecraft:bread", 20);
            case SLEEP -> new Result.SleepDetail(true, pos());
            default -> null;
        };
        send(MessageType.RESULT, detail == null ? Result.ok(t.commandId()) : Result.ok(t.commandId(), detail));
    }

    private void drift() {
        heading += 0.05;
        x += 0.2 * Math.cos(heading);
        z += 0.2 * Math.sin(heading);
    }

    private Pos pos() {
        return new Pos(round(x), y, round(z));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void sendSnapshot() {
        Pos me = pos();
        OwnerInfo owner = OwnerInfo.seen(4.24, new Pos(round(x + 3), y, round(z + 3)));
        TaskInfo info = task == null ? null : new TaskInfo(task.commandId(), task.name(), "running");
        send(MessageType.SNAPSHOT, new Snapshot(true, me, "minecraft:overworld", 20.0, 20,
                new LinkedHashMap<>(inventory), owner, info, Map.of("minecraft:oak_log", 8)));
    }

    private void send(MessageType type, Object body) {
        sink.send(ProtocolCodec.envelope(type, Peer.MC, Peer.HUB, body));
    }

    /** 测试用：当前有没有任务在跑。 */
    public boolean hasTask() {
        return task != null;
    }
}
