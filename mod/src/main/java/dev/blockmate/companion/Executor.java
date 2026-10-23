package dev.blockmate.companion;

import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalNear;
import com.google.gson.JsonObject;
import dev.blockmate.protocol.Ack;
import dev.blockmate.protocol.Command;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.DeliveryOutcome;
import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Pos;
import dev.blockmate.protocol.Progress;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.Reason;
import dev.blockmate.protocol.Result;
import dev.blockmate.protocol.TaskInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * executor（手册 6.4）：follow / come_here / wait_here / stop / pick_up / deliver / rejoin。
 * 单任务槽：新 command 到达先给旧任务回 result cancelled，再回新任务的 ack。全部在游戏线程上。
 */
public final class Executor implements AbstractGameEventListener {

    private static final int LOST_AFTER_TICKS = 160;      // 主人 8 秒不在视野（10-23 从 10 秒改短，让"10 秒内说跟丢"留出说话的时间）
    private static final double PROGRESS_STEP = 8.0;      // 距离每变化 8 格发 progress
    private static final int STUCK_WINDOW_TICKS = 100;    // 5 秒没动算卡住
    private static final double STUCK_MIN_MOVE = 0.1;
    private static final double ARRIVE_DISTANCE = 2.5;    // come_here：到主人 2 格内（留点余量）
    private static final double DELIVER_DISTANCE = 3.0;   // deliver：到主人 3 格内
    private static final double BACK_OFF_DISTANCE = 3.0;  // 丢完退开至少 3 格
    private static final int STOP_TICKS_PER_ATTEMPT = 20;
    private static final int STOP_MAX_ATTEMPTS = 3;
    private static final int REPLAN_EVERY_TICKS = 40;     // 目标挪了就每 2 秒更新一次
    private static final int PICKUP_TIMEOUT_TICKS = 300;  // 15 秒没捡到
    private static final int PICKUP_NONE_LEFT_TICKS = 40; // 半径内没东西了再等 2 秒就放弃
    private static final int BACK_OFF_MAX_TICKS = 100;
    private static final long CONFIRM_WINDOW_MS = 10_000; // 丢出后等确认 10 秒

    private enum Phase { APPROACH, THROW, BACK_OFF, WAIT_CONFIRM }

    private static final class Task {
        final String id;
        final CommandName name;
        final JsonObject args;
        final int startTick;
        FollowWatch follow;
        StuckDetector stuck;
        StopTracker stop;
        Pos devGoal;          // come_here 的开发后门：主人不在时走到指定坐标
        BlockPos lastGoal;
        int lastReplanTick;
        boolean pathFailed;
        // pick_up
        String item;
        int radius;
        int startCount;
        int noneLeftSince = -1;
        // deliver
        Phase phase = Phase.APPROACH;
        String targetName;
        int targetEntityId = -1;
        int wanted;
        ThrowPlan.Plan plan;
        int clickIndex;
        long thrownAtMs;
        int phaseStartTick;

        Task(String id, CommandName name, JsonObject args, int startTick) {
            this.id = id;
            this.name = name;
            this.args = args;
            this.startTick = startTick;
        }
    }

    private final Supplier<HubLink> link;
    private final Runnable reportStatus;
    private final Rejoiner rejoiner;
    private Task current;
    private int tick;
    private IBaritone listeningOn;

    public Executor(Supplier<HubLink> link, Runnable reportStatus, Rejoiner rejoiner) {
        this.link = link;
        this.reportStatus = reportStatus;
        this.rejoiner = rejoiner;
    }

    public TaskInfo currentTaskInfo() {
        return current == null ? null : new TaskInfo(current.id, current.name, "running");
    }

    /** 进入世界时调用：向当前的 Baritone 注册路径事件监听（只注册一次）。 */
    public void attachBaritone() {
        IBaritone b = BaritoneBridge.baritone();
        if (b != null && b != listeningOn) {
            BaritoneBridge.registerListener(this);
            listeningOn = b;
        }
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (current != null && (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED)) {
            current.pathFailed = true;
        }
    }

    // ---------------- 命令 ----------------

    public void onCommand(Envelope env) {
        Command cmd = ProtocolCodec.bodyAs(env, Command.class);
        Minecraft mc = Minecraft.getInstance();
        if (cmd.name() == null || mc.player == null || mc.level == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "command rejected (unknown or not in world)");
            send(MessageType.ACK, Ack.rejected(env.id()));
            return;
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "command {} id={}", cmd.name().wire(), env.id());
        switch (cmd.name()) {
            case STOP -> startStop(env.id());
            case CANCEL -> {
                Command.CancelArgs a = cmd.argsAs(Command.CancelArgs.class);
                send(MessageType.ACK, Ack.accepted(env.id()));
                if (current != null && (a == null || a.commandId() == null || a.commandId().equals(current.id))) {
                    cancelCurrent();
                }
                send(MessageType.RESULT, Result.ok(env.id()));
            }
            case REPORT_STATUS -> {
                send(MessageType.ACK, Ack.accepted(env.id()));
                reportStatus.run();
                send(MessageType.RESULT, Result.ok(env.id()));
            }
            case WAIT_HERE -> {
                cancelCurrent();
                send(MessageType.ACK, Ack.accepted(env.id()));
                BaritoneBridge.cancelEverything();
                send(MessageType.RESULT, Result.ok(env.id()));
            }
            case FOLLOW -> startFollow(env.id(), cmd.args(), mc);
            case COME_HERE -> startComeHere(env.id(), cmd.args(), mc);
            case PICK_UP -> startPickUp(env.id(), cmd.args(), mc);
            case DELIVER -> startDeliver(env.id(), cmd.args(), mc);
            case REJOIN -> startRejoin(env.id(), mc);
            default -> {
                // 任务单 9 加的新命令，模组还没实现：按 handbook 7.1 立刻拒绝，别让 hub 等 3 秒判无响应
                CompanionMod.LOG.info(CompanionMod.TAG + "command {} not implemented yet -> rejected", cmd.name().wire());
                send(MessageType.ACK, Ack.rejected(env.id()));
            }
        }
    }

    private void startFollow(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        Task t = new Task(id, CommandName.FOLLOW, args, tick);
        t.follow = new FollowWatch(LOST_AFTER_TICKS, PROGRESS_STEP, tick, Observer.dim(mc));
        current = t;
        send(MessageType.ACK, Ack.accepted(id));
        String owner = ClientConfig.OWNER_NAME.get();
        LocalPlayer me = mc.player;
        b.getFollowProcess().follow(e -> e != me && e instanceof Player p && owner.equals(p.getGameProfile().getName()));
        CompanionMod.LOG.info(CompanionMod.TAG + "follow started owner={} visible={}", owner, findOwner(mc) != null);
    }

    private void startComeHere(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        Task t = new Task(id, CommandName.COME_HERE, args, tick);
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        Player owner = findOwner(mc);
        if (owner == null && args != null && args.has("x") && args.has("y") && args.has("z")) {
            t.devGoal = new Pos(args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble());
            CompanionMod.LOG.info(CompanionMod.TAG + "come_here dev goal {}", t.devGoal);
        }
        if (owner == null && t.devGoal == null) {
            send(MessageType.ACK, Ack.accepted(id));
            send(MessageType.RESULT, Result.failed(id, Reason.TARGET_LOST));
            CompanionMod.LOG.info(CompanionMod.TAG + "come_here: owner not visible -> target_lost");
            return;
        }
        current = t;
        send(MessageType.ACK, Ack.accepted(id));
        setGoal(b, t, goalPos(owner, t), 2);
    }

    private void startPickUp(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        Command.PickUpArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.PickUpArgs.class);
        String item = normalizeItem(a == null ? null : a.item());
        int radius = a == null ? 8 : a.radiusOrDefault();
        send(MessageType.ACK, Ack.accepted(id));
        LocalPlayer me = mc.player;
        if (item == null) {
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND));
            return;
        }
        if (findItems(mc, me, item, radius).isEmpty()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "pick_up: no {} within {} blocks", item, radius);
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND));
            return;
        }
        if (inventoryFull(me, item)) {
            finish(Result.failed(id, Reason.INVENTORY_FULL));
            return;
        }
        Task t = new Task(id, CommandName.PICK_UP, args, tick);
        t.item = item;
        t.radius = radius;
        t.startCount = countItem(me, item);
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        current = t;
        b.getFollowProcess().pickup(stack -> item.equals(idOf(stack)));
        CompanionMod.LOG.info(CompanionMod.TAG + "pick_up started item={} radius={} have={}", item, radius, t.startCount);
    }

    private void startDeliver(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        Command.DeliverArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.DeliverArgs.class);
        String item = normalizeItem(a == null ? null : a.item());
        String targetName = a != null && a.to() != null && !a.to().isBlank() ? a.to() : ClientConfig.OWNER_NAME.get();
        send(MessageType.ACK, Ack.accepted(id));
        LocalPlayer me = mc.player;
        Player target = findPlayer(mc, targetName);
        if (target == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "deliver: {} not visible -> target_lost", targetName);
            finish(Result.failed(id, Reason.TARGET_LOST, new Result.DeliverDetail(DeliveryOutcome.NOT_THROWN, 0)));
            return;
        }
        int have = item == null ? 0 : countItem(me, item);
        if (have == 0) {
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND, new Result.DeliverDetail(DeliveryOutcome.NOT_THROWN, 0)));
            return;
        }
        Task t = new Task(id, CommandName.DELIVER, args, tick);
        t.item = item;
        t.targetName = targetName;
        t.wanted = a.count() > 0 ? Math.min(a.count(), have) : have;
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        t.phase = Phase.APPROACH;
        t.phaseStartTick = tick;
        current = t;
        setGoal(b, t, target.blockPosition(), 2);
        CompanionMod.LOG.info(CompanionMod.TAG + "deliver started item={} count={} to={}", item, t.wanted, targetName);
    }

    private void startRejoin(String id, Minecraft mc) {
        if (!rejoiner.enabled()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "rejoin: no server address -> rejected");
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        cancelCurrent();
        send(MessageType.ACK, Ack.accepted(id));
        int attempt = rejoiner.forceRejoin(mc);
        send(MessageType.RESULT, Result.ok(id, new Result.RejoinDetail(attempt)));
    }

    private void startStop(String id) {
        if (current != null) {
            send(MessageType.RESULT, Result.cancelled(current.id));
            current = null;
        }
        boolean r = BaritoneBridge.cancelEverything();
        Task t = new Task(id, CommandName.STOP, null, tick);
        t.stop = new StopTracker(STOP_TICKS_PER_ATTEMPT, STOP_MAX_ATTEMPTS, tick);
        current = t;
        send(MessageType.ACK, Ack.accepted(id));
        CompanionMod.LOG.info(CompanionMod.TAG + "stop: cancelEverything -> {}", r);
    }

    private void cancelCurrent() {
        if (current == null) {
            return;
        }
        send(MessageType.RESULT, Result.cancelled(current.id));
        CompanionMod.LOG.info(CompanionMod.TAG + "task {} cancelled (replaced)", current.name.wire());
        current = null;
        BaritoneBridge.cancelEverything();
    }

    /** 与后台断开：立刻停，丢掉任务，不补发 result（细则第 15 节）。 */
    public void onLinkLost() {
        if (current != null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "link lost, dropping task {}", current.name.wire());
            current = null;
        }
        BaritoneBridge.cancelEverything();
    }

    /** 离开世界。 */
    public void reset() {
        current = null;
    }

    // ---------------- 每 tick ----------------

    public void onTick(Minecraft mc) {
        tick++;
        if (current == null || mc.player == null || mc.level == null) {
            return;
        }
        LocalPlayer me = mc.player;
        if (me.isDeadOrDying()) {
            finish(Result.failed(current.id, Reason.DIED));
            BaritoneBridge.cancelEverything();
            return;
        }
        switch (current.name) {
            case FOLLOW -> tickFollow(mc, me);
            case COME_HERE -> tickComeHere(mc, me);
            case STOP -> tickStop(mc, me);
            case PICK_UP -> tickPickUp(mc, me);
            case DELIVER -> tickDeliver(mc, me);
            default -> {
            }
        }
    }

    private void tickFollow(Minecraft mc, LocalPlayer me) {
        Task t = current;
        Player owner = findOwner(mc);
        FollowWatch.Outcome o = t.follow.feed(owner != null, Observer.dim(mc), tick);
        if (o == FollowWatch.Outcome.TARGET_LOST) {
            // 记一下主人是离开了服务器还是只是超出了实体同步范围，下次排"重进后认不出"时有据可查
            boolean inTabList = mc.getConnection() != null && mc.getConnection().getOnlinePlayers().stream()
                    .anyMatch(i -> ClientConfig.OWNER_NAME.get().equals(i.getProfile().getName()));
            CompanionMod.LOG.info(CompanionMod.TAG + "follow: target lost, owner in tab list={} (false = 离开了服务器, true = 超出同步范围)", inTabList);
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.TARGET_LOST));
            return;
        }
        if (o == FollowWatch.Outcome.TARGET_OTHER_DIM) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.TARGET_OTHER_DIM));
            return;
        }
        if (owner != null) {
            double dist = me.distanceTo(owner);
            if (t.follow.progressDue(dist)) {
                send(MessageType.PROGRESS, new Progress(t.id, "distance", round(dist), Observer.pos(me)));
            }
        }
    }

    private void tickComeHere(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        Player owner = findOwner(mc);
        if (owner == null && t.devGoal == null) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.TARGET_LOST));
            return;
        }
        Vec3 target = owner != null ? owner.position() : new Vec3(t.devGoal.x(), t.devGoal.y(), t.devGoal.z());
        double dist = me.position().distanceTo(target);
        if (dist <= ARRIVE_DISTANCE) {
            BaritoneBridge.cancelEverything();
            finish(Result.ok(t.id));
            return;
        }
        if (t.pathFailed) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.PATH_FAILED));
            return;
        }
        if (t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick)) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.STUCK));
            return;
        }
        if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS) {
            BlockPos goal = goalPos(owner, t);
            boolean idle = !b.getPathingBehavior().isPathing() && !b.getCustomGoalProcess().isActive();
            if (idle || t.lastGoal == null || goal.distSqr(t.lastGoal) > 9) {
                setGoal(b, t, goal, 2);
            }
        }
    }

    private void tickStop(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        Vec3 v = me.getDeltaMovement();
        boolean baritoneIdle = b == null || (!b.getPathingBehavior().isPathing() && !b.getFollowProcess().isActive()
                && !b.getCustomGoalProcess().isActive());
        boolean stationary = Math.hypot(v.x, v.z) < 0.01 && baritoneIdle;
        StopTracker.Verdict verdict = t.stop.feed(stationary, tick);
        switch (verdict) {
            case DONE_STATIONARY -> finish(Result.ok(t.id, new Result.StopDetail(true, t.stop.attempts())));
            case RETRY -> {
                CompanionMod.LOG.info(CompanionMod.TAG + "stop: still moving, cancelEverything again (attempt {})", t.stop.attempts());
                BaritoneBridge.cancelEverything();
            }
            case GIVE_UP -> finish(Result.failed(t.id, Reason.TIMEOUT, new Result.StopDetail(false, t.stop.attempts())));
            default -> {
            }
        }
    }

    private void tickPickUp(Minecraft mc, LocalPlayer me) {
        Task t = current;
        int now = countItem(me, t.item);
        if (now > t.startCount) {
            BaritoneBridge.cancelEverything();
            finish(Result.ok(t.id, new Result.PickUpDetail(now - t.startCount)));
            return;
        }
        if (tick - t.startTick >= PICKUP_TIMEOUT_TICKS) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.NOT_PICKED));
            return;
        }
        if (inventoryFull(me, t.item)) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.INVENTORY_FULL));
            return;
        }
        if (t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick)) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.STUCK));
            return;
        }
        // 半径内已经没有那种东西（被别人捡了、烧了）：再等 2 秒就放弃，不干等 15 秒
        if (findItems(mc, me, t.item, t.radius * 2).isEmpty()) {
            if (t.noneLeftSince < 0) {
                t.noneLeftSince = tick;
            } else if (tick - t.noneLeftSince >= PICKUP_NONE_LEFT_TICKS) {
                BaritoneBridge.cancelEverything();
                finish(Result.failed(t.id, Reason.NOT_PICKED));
                return;
            }
        } else {
            t.noneLeftSince = -1;
        }
    }

    private void tickDeliver(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        Player target = findPlayer(mc, t.targetName);
        switch (t.phase) {
            case APPROACH -> {
                if (target == null) {
                    BaritoneBridge.cancelEverything();
                    finish(Result.failed(t.id, Reason.TARGET_LOST, new Result.DeliverDetail(DeliveryOutcome.NOT_THROWN, 0)));
                    return;
                }
                double dist = me.distanceTo(target);
                if (dist <= DELIVER_DISTANCE) {
                    BaritoneBridge.cancelEverything();
                    me.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
                    t.plan = ThrowPlan.plan(stacksOf(me, t.item), t.wanted);
                    t.clickIndex = 0;
                    t.targetEntityId = target.getId();
                    t.phase = Phase.THROW;
                    t.phaseStartTick = tick;
                    CompanionMod.LOG.info(CompanionMod.TAG + "deliver: arrived ({} blocks), throwing {} in {} clicks", round(dist), t.plan.total(), t.plan.clicks().size());
                    return;
                }
                if (t.pathFailed) {
                    BaritoneBridge.cancelEverything();
                    finish(Result.failed(t.id, Reason.PATH_FAILED, new Result.DeliverDetail(DeliveryOutcome.NOT_THROWN, 0)));
                    return;
                }
                if (t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick)) {
                    BaritoneBridge.cancelEverything();
                    finish(Result.failed(t.id, Reason.STUCK, new Result.DeliverDetail(DeliveryOutcome.NOT_THROWN, 0)));
                    return;
                }
                if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS) {
                    BlockPos goal = target.blockPosition();
                    boolean idle = !b.getPathingBehavior().isPathing() && !b.getCustomGoalProcess().isActive();
                    if (idle || t.lastGoal == null || goal.distSqr(t.lastGoal) > 9) {
                        setGoal(b, t, goal, 2);
                    }
                }
            }
            case THROW -> {
                if (t.clickIndex < t.plan.clicks().size()) {
                    ThrowPlan.Click c = t.plan.clicks().get(t.clickIndex++);
                    if (mc.gameMode != null) {
                        // 必须走会发包给服务器的路径（手册 6.4 第 6 条），不能用只在本地画假物品的 Player.drop
                        mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, c.slot(), c.button(), ClickType.THROW, me);
                    }
                    return;
                }
                t.thrownAtMs = System.currentTimeMillis();
                t.phase = Phase.BACK_OFF;
                t.phaseStartTick = tick;
                Vec3 away = target == null ? new Vec3(1, 0, 0) : me.position().subtract(target.position());
                away = new Vec3(away.x, 0, away.z);
                if (away.lengthSqr() < 0.01) {
                    away = new Vec3(1, 0, 0);
                }
                Vec3 p = me.position().add(away.normalize().scale(4));
                if (b != null) {
                    setGoal(b, t, BlockPos.containing(p.x, p.y, p.z), 1);
                }
                CompanionMod.LOG.info(CompanionMod.TAG + "deliver: thrown {}, backing off", t.plan.total());
            }
            case BACK_OFF -> {
                if (DeliveryWatch.confirmedSince(t.targetEntityId, t.item, t.thrownAtMs)) {
                    BaritoneBridge.cancelEverything();
                    finish(Result.ok(t.id, new Result.DeliverDetail(DeliveryOutcome.CONFIRMED, t.plan.total())));
                    return;
                }
                boolean farEnough = target == null || me.distanceTo(target) >= BACK_OFF_DISTANCE;
                if (farEnough || tick - t.phaseStartTick >= BACK_OFF_MAX_TICKS) {
                    BaritoneBridge.cancelEverything();
                    t.phase = Phase.WAIT_CONFIRM;
                    t.phaseStartTick = tick;
                }
            }
            case WAIT_CONFIRM -> {
                if (DeliveryWatch.confirmedSince(t.targetEntityId, t.item, t.thrownAtMs)) {
                    finish(Result.ok(t.id, new Result.DeliverDetail(DeliveryOutcome.CONFIRMED, t.plan.total())));
                    return;
                }
                if (System.currentTimeMillis() - t.thrownAtMs >= CONFIRM_WINDOW_MS) {
                    // 结果未知：绝不自动再丢一次（手册 6.4 第 6 条）
                    finish(Result.ok(t.id, new Result.DeliverDetail(DeliveryOutcome.THROWN_UNCONFIRMED, t.plan.total())));
                }
            }
        }
    }

    // ---------------- 小工具 ----------------

    private void setGoal(IBaritone b, Task t, BlockPos goal, int range) {
        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, range));
        t.lastGoal = goal;
        t.lastReplanTick = tick;
        t.pathFailed = false;
    }

    private static BlockPos goalPos(Player owner, Task t) {
        if (owner != null) {
            return owner.blockPosition();
        }
        return BlockPos.containing(t.devGoal.x(), t.devGoal.y(), t.devGoal.z());
    }

    static Player findOwner(Minecraft mc) {
        return findPlayer(mc, ClientConfig.OWNER_NAME.get());
    }

    static Player findPlayer(Minecraft mc, String name) {
        if (mc.level == null || name == null) {
            return null;
        }
        for (Player p : mc.level.players()) {
            if (p != mc.player && name.equals(p.getGameProfile().getName())) {
                return p;
            }
        }
        return null;
    }

    /** 物品 id 规范化：没写命名空间就补 minecraft:；不存在的物品返回 null。 */
    static String normalizeItem(String item) {
        if (item == null || item.isBlank()) {
            return null;
        }
        String id = item.strip().toLowerCase();
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) {
            return null;
        }
        return id;
    }

    static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    static int countItem(LocalPlayer me, String item) {
        int n = 0;
        for (ItemStack s : me.getInventory().items) {
            if (item.equals(idOf(s))) {
                n += s.getCount();
            }
        }
        for (ItemStack s : me.getInventory().offhand) {
            if (item.equals(idOf(s))) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** 主背包里该物品的每一组（格子序号, 数量），快捷栏优先。 */
    static List<ThrowPlan.Stack> stacksOf(LocalPlayer me, String item) {
        List<ThrowPlan.Stack> out = new ArrayList<>();
        var items = me.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (item.equals(idOf(s))) {
                out.add(new ThrowPlan.Stack(i, s.getCount()));
            }
        }
        return out;
    }

    static boolean inventoryFull(LocalPlayer me, String item) {
        if (me.getInventory().getFreeSlot() != -1) {
            return false;
        }
        Item it = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item));
        return it == null || me.getInventory().getSlotWithRemainingSpace(new ItemStack(it)) == -1;
    }

    static List<ItemEntity> findItems(Minecraft mc, LocalPlayer me, String item, int radius) {
        return mc.level.getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(radius),
                e -> item.equals(idOf(e.getItem())));
    }

    private void finish(Result r) {
        CompanionMod.LOG.info(CompanionMod.TAG + "result {} {} {}", r.commandId(), r.status(), r.reason() == null ? "" : r.reason().wire());
        send(MessageType.RESULT, r);
        current = null;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private void send(MessageType type, Object body) {
        HubLink l = link.get();
        if (l != null) {
            l.send(l.envelope(type, body));
        }
    }
}
