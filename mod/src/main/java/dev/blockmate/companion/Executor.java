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
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import dev.blockmate.protocol.Event;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * executor（手册 6.4）：follow / come_here / wait_here / stop / pick_up / deliver / rejoin；任务单 13 加 goto / mine / collect；任务单 14 加 craft / place / pickup_block / use_furnace；任务单 15 加 eat / sleep 和两条被动行为（饿了自动吃、睡醒收床）。
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
    // 任务单 13
    private static final int MINE_STALL_TICKS = 400;      // 20 秒没多挖到一个算卡住
    private static final int MINE_PROGRESS_TICKS = 60;    // 每 3 秒报一次已挖数
    private static final int MINE_GRACE_TICKS = 60;       // 开挖后 3 秒内 Baritone 还没动起来不算"找不到"
    private static final int MINE_MAX_RADIUS = 48;
    private static final int COLLECT_NONE_LEFT_TICKS = 40; // 半径内没剩东西再等 2 秒就算捡完
    // 任务单 14
    private static final int CRAFT_RESULT_WAIT_TICKS = 60;   // 点完结果格后等 3 秒背包没多东西算服务器没认
    private static final int PLACE_SETTLE_TICKS = 3;         // 换到快捷栏后等几 tick 再放
    private static final int PLACE_CONFIRM_TICKS = 20;       // 放下去后等 1 秒确认方块出现
    private static final int BREAK_TIMEOUT_TICKS = 400;      // 20 秒还没挖掉（徒手拆工作台 ~4 秒，拿镐拆熔炉 ~1 秒）
    private static final int PICK_TIMEOUT_TICKS = 100;       // 挖掉后 5 秒内要捡到
    private static final int PLACE_HOTBAR_SLOT = 8;          // 放方块用快捷栏最后一格
    private static final int STATION_RADIUS = 8;             // 工作台 / 熔炉在 8 格内才算"附近有"
    private static final int MENU_OPEN_TIMEOUT_TICKS = 40;   // 对工作台 / 熔炉右键后 2 秒内菜单要打开
    private static final int FURNACE_CHECK_TICKS = 40;       // 每 2 秒看一次熔炉
    private static final int FURNACE_UNLIT_TICKS = 100;      // 原料还在、炉子灭了 5 秒且没燃料 = 燃料用完
    // 任务单 15
    private static final int EAT_TIMEOUT_TICKS = 120;        // 按住使用键 6 秒还没吃掉算卡住（吃一口 32 tick）
    private static final int SLEEP_CONFIRM_TICKS = 60;       // 右键床后 3 秒内要躺下
    private static final int AUTO_EAT_RETRY_TICKS = 600;     // 自动吃失败（没食物）30 秒后再试

    private enum Phase {
        APPROACH, THROW, BACK_OFF, WAIT_CONFIRM, BREAK, PICK,
        // 任务单 14 第二批：工作台 / 熔炉
        STATION_PLACE, STATION_APPROACH, STATION_OPEN, TABLE_CRAFT, FURNACE_LOAD, FURNACE_WAIT, STATION_PICKUP,
        // 任务单 15
        EAT_SELECT, EAT_CHEW, BED_PICKUP
    }

    private enum OpState { RUNNING, DONE, FAILED }

    /** 一次背包菜单点击（任务单 14），发包给服务器裁决。 */
    private record Click(int slot, int button, ClickType type) {
    }

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
        // mine / collect（任务单 13）
        String blockId;
        net.minecraft.world.level.block.Block block;
        GatherLogic.MineProgress mine;
        int lastProgressTick;
        // fight（任务单 20）
        String fightKind;
        // lead（任务单 21）
        String leadLandmark;
        boolean leadWaiting;
        String leadDanger;
        int leadPauseStart;
        int ownerMissingSince = -1;
        int lastDangerCheckTick;
        // 传送门 / 探路（任务单 22）
        List<PortalLogic.Placement> portalPlan;
        int portalIndex;
        PortalLogic.Site portalSite;
        String portalBlock;
        String scaffoldId;
        List<PortalLogic.Cell> scaffoldCells = List.of();
        int scaffoldIndex;
        int portalPlaced;
        int portalRetry;
        PortalLogic.Cell portalInside;
        PortalLogic.Cell portalLight;
        List<PortalLogic.Cell> frameCells = List.of();
        BlockPos standPos;
        String startDim;
        long exploreStartMs;
        boolean exploreWaiting;
        int collectStart;
        // craft / place / pickup_block（任务单 14）
        ArrayDeque<Click> clicks;
        List<CraftPlanner.Cell> cells;
        int batchesLeft;
        int perCraft;
        int batchStartCount;
        int waitSince = -1;
        BlockPos placePos;
        // 子动作：放一个方块 / 把自己放的挖回来（任务单 14 第二批抽出来，place / pickup_block / 工作台 / 熔炉共用）
        BlockPos opPos;
        String opBlockId;
        Block opBlock;
        int opStage;
        int opStartTick;
        boolean opBreaking;
        int opStartCount;
        Reason opReason;
        // 工作台 / 熔炉
        boolean useTable;
        BlockPos stationPos;
        boolean selfPlaced;
        int openSince = -1;
        Result pending;
        String fuelId;
        Integer smeltCount;
        int smelted;
        int unlitSince = -1;
        int lastCheckTick;
        // 任务单 15
        boolean internal;     // 模组自己发起的（自动吃、收床），不回 ack / result
        int foodBefore;
        boolean dugOut;       // 走不出去时已经试过一次"准挖自然方块"
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
    /** 任务单 19：自保；打架时任务暂停（pausedTask 记的是暂停时的那个任务）。 */
    private final Combat combat = new Combat(this::send);
    private boolean pausedForCombat;
    private Task pausedTask;
    /** 任务单 22：最近搭的门（点火 / 进门用）；换维度时 Baritone 参数跟着换。 */
    private record PortalMemory(PortalLogic.Site site, PortalLogic.Cell light, PortalLogic.Cell inside, String dim) {
    }
    private PortalMemory lastPortal;
    private String lastDim;
    private static final int PORTAL_WALK_TIMEOUT_TICKS = 400;
    private static final int PORTAL_REACH_TIMEOUT_TICKS = 200;
    private static final int SCAFFOLD_BREAK_TIMEOUT_TICKS = 200;
    private static final int LIGHT_CONFIRM_TICKS = 40;
    private static final int ENTER_TIMEOUT_TICKS = 1200;
    private static final int EXPLORE_TIMEOUT_TICKS = 6000;
    private static final int EXPLORE_REISSUE_TICKS = 60;
    private static final List<String> SCAFFOLD_CANDIDATES = List.of("minecraft:cobblestone", "minecraft:dirt", "minecraft:netherrack",
            "minecraft:cobbled_deepslate", "minecraft:stone", "minecraft:oak_planks");
    private IBaritone listeningOn;

    /** hello 下发的白名单（任务单 12）；没收到之前全不许。 */
    private volatile Rules rules = Rules.EMPTY;

    /** 自己放过的方块（任务单 14）：方块 id → 位置，pickup_block 只认这里的。 */
    private final Map<String, BlockPos> placedBlocks = new HashMap<>();

    // 任务单 15：睡醒后要收的床、自动吃的节流
    private BlockPos bedToCollect;
    private String bedToCollectId;
    private boolean wasSleeping;
    private int autoEatNotBefore;
    private int internalSeq;

    public void setRules(Rules rules) {
        this.rules = rules == null ? Rules.EMPTY : rules;
    }

    public Rules rules() {
        return rules;
    }

    public Executor(Supplier<HubLink> link, Runnable reportStatus, Rejoiner rejoiner) {
        this.link = link;
        this.reportStatus = reportStatus;
        this.rejoiner = rejoiner;
    }

    public TaskInfo currentTaskInfo() {
        return current == null ? null : new TaskInfo(current.id, current.name, pausedForCombat ? "paused" : "running");
    }

    public boolean inCombat() {
        return combat.active();
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
        if (mc.player != null && mc.player.isSleeping() && cmd.name() != CommandName.REPORT_STATUS) {
            wakeUp(mc); // 躺着什么都做不了：先起床（任务单 15）
        }
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
            case GOTO -> startGoto(env.id(), cmd.args(), mc);
            case MINE -> startMine(env.id(), cmd.args(), mc);
            case COLLECT -> startCollect(env.id(), cmd.args(), mc);
            case CRAFT -> startCraft(env.id(), cmd.args(), mc);
            case PLACE -> startPlace(env.id(), cmd.args(), mc);
            case PICKUP_BLOCK -> startPickupBlock(env.id(), cmd.args(), mc);
            case USE_FURNACE -> startUseFurnace(env.id(), cmd.args(), mc);
            case EAT -> startEat(env.id(), cmd.args(), mc, false);
            case SLEEP -> startSleep(env.id(), cmd.args(), mc);
            case FIGHT -> startFight(env.id(), cmd.args(), mc);
            case LEAD -> startLead(env.id(), cmd.args(), mc);
            case BUILD_PORTAL -> startBuildPortal(env.id(), cmd.args(), mc);
            case LIGHT_PORTAL -> startLightPortal(env.id(), mc);
            case ENTER_PORTAL -> startEnterPortal(env.id(), mc);
            case EXPLORE -> startExplore(env.id(), mc);
            case CONTINUE -> {
                // 任务单 21：不占任务槽；带路因危险停着就接着走
                send(MessageType.ACK, Ack.accepted(env.id()));
                if (current != null && current.name == CommandName.LEAD && current.leadDanger != null) {
                    current.leadDanger = null;
                    current.lastDangerCheckTick = tick + 40; // 刚被叫走，2 秒内别又停
                    IBaritone b = BaritoneBridge.baritone();
                    if (b != null) {
                        setGoal(b, current, goalPos(null, current), 2);
                    }
                    CompanionMod.LOG.info(CompanionMod.TAG + "lead: continue after danger");
                } else {
                    CompanionMod.LOG.info(CompanionMod.TAG + "continue: nothing paused");
                }
                send(MessageType.RESULT, Result.ok(env.id()));
            }
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
        releaseUseKey();
        if (current != null) {
            if (!current.internal) {
                send(MessageType.RESULT, Result.cancelled(current.id));
            }
            closeMenuIfOpen(current);
            current = null;
        }
        boolean r = BaritoneBridge.cancelEverything();
        BreakGuard.closeAll("stop");
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
        if (current.name == CommandName.FIGHT) {
            combat.cancelHunt();
        }
        releaseUseKey();
        if (!current.internal) {
            send(MessageType.RESULT, Result.cancelled(current.id));
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "task {} cancelled (replaced, internal={})", current.name.wire(), current.internal);
        closeMenuIfOpen(current);
        current = null;
        BaritoneBridge.cancelEverything();
        BreakGuard.closeAll("task replaced");
    }

    /** 与后台断开：立刻停，丢掉任务，不补发 result（细则第 15 节）。 */
    public void onLinkLost() {
        combat.cancelHunt();
        releaseUseKey();
        if (current != null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "link lost, dropping task {}", current.name.wire());
            closeMenuIfOpen(current);
            current = null;
        }
        BaritoneBridge.cancelEverything();
        BreakGuard.closeAll("link lost");
    }

    /** 离开世界。 */
    public void reset() {
        releaseUseKey();
        current = null;
        bedToCollect = null;
        wasSleeping = false;
        BreakGuard.closeAll("left world");
    }

    private static void releaseUseKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.keyUse.isDown()) {
            mc.options.keyUse.setDown(false);
            if (mc.player != null && mc.player.isUsingItem() && mc.gameMode != null) {
                mc.gameMode.releaseUsingItem(mc.player);
            }
        }
    }

    // ---------------- 每 tick ----------------

    public void onTick(Minecraft mc) {
        tick++;
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 任务单 22：换了维度就换 Baritone 的参数
        String dimNow = Observer.dim(mc);
        if (dimNow != null && !dimNow.equals(lastDim)) {
            lastDim = dimNow;
            BaritoneBridge.applyDimensionSettings(dimNow);
        }
        // 任务单 19：自保跑在所有任务前面；"停"最高优先，先收手
        if (combat.active() && current != null && current.name == CommandName.STOP) {
            combat.stop(mc, mc.player, tick, "stop command");
        }
        boolean fighting = combat.tick(mc, mc.player, rules, tick);
        boolean fightTask = current != null && current.name == CommandName.FIGHT;
        if (fighting && !fightTask) {
            if (!pausedForCombat || current != pausedTask) {
                pauseForCombat(mc, mc.player);
            }
            return;
        }
        if (pausedForCombat) {
            resumeAfterCombat(mc, mc.player);
        }
        tickPassive(mc, mc.player);
        if (current == null) {
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
            case GOTO -> tickGoto(mc, me);
            case MINE -> tickMine(mc, me);
            case COLLECT -> tickCollect(mc, me);
            case CRAFT -> tickCraft(mc, me);
            case PLACE -> tickPlace(mc, me);
            case USE_FURNACE -> tickUseFurnace(mc, me);
            case EAT -> tickEat(mc, me);
            case SLEEP -> tickSleep(mc, me);
            case FIGHT -> tickFight(mc, me);
            case LEAD -> tickLead(mc, me);
            case BUILD_PORTAL -> tickBuildPortal(mc, me);
            case LIGHT_PORTAL -> tickLightPortal(mc, me);
            case ENTER_PORTAL -> tickEnterPortal(mc, me);
            case EXPLORE -> tickExplore(mc, me);
            case PICKUP_BLOCK -> {
                if (current.internal) {
                    tickBedPickup(mc, me);
                } else {
                    tickPickupBlock(mc, me);
                }
            }
            default -> {
            }
        }
    }

    // ---------------- 任务单 19：打架时暂停、打完恢复 ----------------

    /** 打架开始（或打架中来了新任务）：松开使用键、关菜单、停 Baritone，发一条 progress "combat"；任务对象留着。 */
    private void pauseForCombat(Minecraft mc, LocalPlayer me) {
        pausedForCombat = true;
        pausedTask = current;
        if (!combat.holdingUseKey()) {
            releaseUseKey();
        }
        if (current != null) {
            closeMenuIfOpen(current);
        }
        BaritoneBridge.cancelEverything();
        if (current != null && !current.internal) {
            send(MessageType.PROGRESS, new Progress(current.id, "combat", null, Observer.pos(me)));
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "task {} paused for combat", current == null ? "none" : current.name.wire());
    }

    /**
     * 打完了：长任务把 Baritone 的活重新派下去（跟随 / 挖 / 捡 / 走到），菜单类的短任务（合成、放、熔炉、吃、睡）中途断了接不上，
     * 按 stuck 结束让后台的计划逻辑决定重试还是跳过；内部任务（自动吃、收床）直接丢掉。
     */
    private void resumeAfterCombat(Minecraft mc, LocalPlayer me) {
        pausedForCombat = false;
        pausedTask = null;
        Task t = current;
        if (t == null) {
            return;
        }
        if (t.internal) {
            current = null;
            return;
        }
        IBaritone b = BaritoneBridge.baritone();
        if (t.stuck != null) {
            t.stuck.reset();
        }
        switch (t.name) {
            case FOLLOW -> {
                if (b != null) {
                    String owner = ClientConfig.OWNER_NAME.get();
                    b.getFollowProcess().follow(e -> e != me && e instanceof Player p && owner.equals(p.getGameProfile().getName()));
                }
            }
            case MINE -> {
                if (b != null && t.block != null) {
                    b.getMineProcess().mine(t.block);
                }
            }
            case PICK_UP, COLLECT -> {
                if (b != null) {
                    String want = t.item;
                    b.getFollowProcess().pickup(stack -> want == null || want.equals(idOf(stack)));
                }
            }
            case GOTO, COME_HERE, DELIVER, STOP, WAIT_HERE, REJOIN, LEAD, ENTER_PORTAL, BUILD_PORTAL, LIGHT_PORTAL -> {
                t.lastGoal = null;
                t.lastReplanTick = 0;
                t.waitSince = -1;
            }
            case EXPLORE -> {
                if (b != null) {
                    b.getExploreProcess().explore(me.getBlockX(), me.getBlockZ());
                }
            }
            default -> {
                CompanionMod.LOG.info(CompanionMod.TAG + "task {} can't resume after combat -> stuck", t.name.wire());
                closeMenuIfOpen(t);
                finish(Result.failed(t.id, Reason.STUCK));
                BaritoneBridge.cancelEverything();
                return;
            }
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "task {} resumed after combat", t.name.wire());
    }

    // ---------------- 任务单 15：被动行为（没任务时自己做） ----------------

    /** 每 tick 在 onTick 开头调：饿了自动吃；睡醒了把自己放的床收回。 */
    private void tickPassive(Minecraft mc, LocalPlayer me) {
        boolean sleeping = me.isSleeping();
        if (wasSleeping && !sleeping) {
            CompanionMod.LOG.info(CompanionMod.TAG + "woke up");
        }
        wasSleeping = sleeping;
        if (current != null || sleeping || me.isDeadOrDying()) {
            return;
        }
        if (bedToCollect != null) {
            Block bed = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(bedToCollectId));
            if (bed == null || !(mc.level.getBlockState(bedToCollect).getBlock() instanceof BedBlock)) {
                CompanionMod.LOG.info(CompanionMod.TAG + "bed at {} is gone, nothing to collect", bedToCollect);
                bedToCollect = null;
                return;
            }
            Task t = new Task("internal-bed-" + (++internalSeq), CommandName.PICKUP_BLOCK, null, tick);
            t.internal = true;
            t.phase = Phase.BED_PICKUP;
            beginBreakOp(t, mc, me, bedToCollectId, bed, bedToCollect);
            current = t;
            CompanionMod.LOG.info(CompanionMod.TAG + "collecting own bed at {}", bedToCollect);
            return;
        }
        if (Survival.hungry(me.getFoodData().getFoodLevel()) && tick >= autoEatNotBefore && rules.received()) {
            startEat("internal-eat-" + (++internalSeq), null, mc, true);
            if (current == null) {
                autoEatNotBefore = tick + AUTO_EAT_RETRY_TICKS; // 没食物：过一会儿再看
            }
        }
    }

    private void tickBedPickup(Minecraft mc, LocalPlayer me) {
        Task t = current;
        OpState st = tickBreakOp(t, mc, me);
        if (st == OpState.RUNNING) {
            return;
        }
        if (st == OpState.DONE) {
            placedBlocks.remove(t.opBlockId);
            send(MessageType.EVENT, Event.bedCollected(new Pos(t.opPos.getX(), t.opPos.getY(), t.opPos.getZ()), Observer.dim(mc)));
            CompanionMod.LOG.info(CompanionMod.TAG + "own bed collected from {}", t.opPos);
        } else {
            CompanionMod.LOG.info(CompanionMod.TAG + "could not collect own bed ({}), leaving it", t.opReason);
        }
        bedToCollect = null;
        current = null;
        BreakGuard.closeAll("bed pickup end");
    }

    private static void wakeUp(Minecraft mc) {
        if (mc.player != null && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
            CompanionMod.LOG.info(CompanionMod.TAG + "wake up requested");
        }
    }

    // ---------------- 任务单 15：eat / sleep ----------------

    /** 背包里能吃的东西（游戏自己知道哪些能吃、饱食度多少）。 */
    static List<Survival.Food> foods(LocalPlayer me) {
        List<Survival.Food> out = new ArrayList<>();
        var items = me.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            if (st.isEmpty() || !st.isEdible() || st.getItem().getFoodProperties() == null) {
                continue;
            }
            out.add(new Survival.Food(i, idOf(st), st.getItem().getFoodProperties().getNutrition()));
        }
        return out;
    }

    private void startEat(String id, JsonObject args, Minecraft mc, boolean internal) {
        if (!internal) {
            cancelCurrent();
            BaritoneBridge.cancelEverything();
            send(MessageType.ACK, Ack.accepted(id));
        }
        LocalPlayer me = mc.player;
        Command.EatArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.EatArgs.class);
        String wanted = a == null ? null : normalizeItem(a.item());
        if (me.getFoodData().getFoodLevel() >= 20) {
            if (internal) {
                return;
            }
            CompanionMod.LOG.info(CompanionMod.TAG + "eat: food is full -> ok, nothing eaten");
            finish(Result.ok(id, new Result.EatDetail(null, 20)));
            return;
        }
        Survival.Food pick = Survival.pick(foods(me), rules.avoidFood(), wanted).orElse(null);
        if (pick == null) {
            if (internal) {
                CompanionMod.LOG.info(CompanionMod.TAG + "hungry ({}) but nothing to eat", me.getFoodData().getFoodLevel());
                return;
            }
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of(wanted == null ? "food" : wanted, 1))));
            return;
        }
        Task t = new Task(id, CommandName.EAT, args, tick);
        t.internal = internal;
        t.item = pick.id();
        t.foodBefore = me.getFoodData().getFoodLevel();
        t.startCount = countItem(me, pick.id());
        int src = pick.inventoryIndex();
        if (src < 9) {
            me.getInventory().selected = src;
        } else {
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, ContainerSlots.slotFor(src, ContainerSlots.INVENTORY_MENU_START),
                    PLACE_HOTBAR_SLOT, ClickType.SWAP, me);
            me.getInventory().selected = PLACE_HOTBAR_SLOT;
        }
        t.phase = Phase.EAT_SELECT;
        t.phaseStartTick = tick;
        current = t;
        CompanionMod.LOG.info(CompanionMod.TAG + "eat started item={} nutrition={} food={} internal={}", pick.id(), pick.nutrition(), t.foodBefore, internal);
    }

    private void tickEat(Minecraft mc, LocalPlayer me) {
        Task t = current;
        if (t.phase == Phase.EAT_SELECT) {
            if (tick - t.phaseStartTick < PLACE_SETTLE_TICKS) {
                return;
            }
            if (!t.item.equals(idOf(me.getMainHandItem()))) {
                CompanionMod.LOG.info(CompanionMod.TAG + "eat: hand holds {} not {} -> stuck", idOf(me.getMainHandItem()), t.item);
                endEat(mc, me, Result.failed(t.id, Reason.STUCK));
                return;
            }
            // 按住"使用"键：游戏自己会开始吃并一直吃到完（和自动吃的模组做法一样）
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(me, InteractionHand.MAIN_HAND);
            t.phase = Phase.EAT_CHEW;
            t.phaseStartTick = tick;
            return;
        }
        int food = me.getFoodData().getFoodLevel();
        if (countItem(me, t.item) < t.startCount || food > t.foodBefore) {
            endEat(mc, me, Result.ok(t.id, new Result.EatDetail(t.item, food)));
            return;
        }
        if (tick - t.phaseStartTick >= EAT_TIMEOUT_TICKS) {
            CompanionMod.LOG.info(CompanionMod.TAG + "eat: nothing happened in {} ticks -> stuck", EAT_TIMEOUT_TICKS);
            endEat(mc, me, Result.failed(t.id, Reason.STUCK));
        }
    }

    private void endEat(Minecraft mc, LocalPlayer me, Result r) {
        mc.options.keyUse.setDown(false);
        if (me.isUsingItem()) {
            mc.gameMode.releaseUsingItem(me);
        }
        Task t = current;
        if (t.internal) {
            current = null;
            if (r.status() == dev.blockmate.protocol.ResultStatus.OK) {
                Result.EatDetail d = r.detailAs(Result.EatDetail.class);
                send(MessageType.EVENT, Event.autoEat(t.item, d == null ? me.getFoodData().getFoodLevel() : d.foodAfter()));
                CompanionMod.LOG.info(CompanionMod.TAG + "auto ate {} -> food {}", t.item, me.getFoodData().getFoodLevel());
            } else {
                autoEatNotBefore = tick + AUTO_EAT_RETRY_TICKS;
            }
            return;
        }
        finish(r);
    }

    /** 任务单 20：fight(kind)——只打这一种且在能打名单上；16 格内找，打完回 ok 带打死数。 */
    private void startFight(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        if (BaritoneBridge.baritone() == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        send(MessageType.ACK, Ack.accepted(id));
        Command.FightArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.FightArgs.class);
        String kind = a == null || a.kind() == null ? "" : Rules.normalize(a.kind());
        if (kind.isEmpty() || !rules.hostile().contains(kind) || "off".equals(rules.combatMode())) {
            CompanionMod.LOG.info(CompanionMod.TAG + "fight: {} not allowed (hostile={} mode={})", kind, rules.hostile().contains(kind), rules.combatMode());
            finish(Result.failed(id, Reason.NOT_ALLOWED));
            return;
        }
        Task t = new Task(id, CommandName.FIGHT, args, tick);
        t.fightKind = kind;
        current = t;
        combat.hunt(kind, tick);
    }

    private void tickFight(Minecraft mc, LocalPlayer me) {
        Task t = current;
        Combat.HuntState st = combat.huntState();
        switch (st) {
            case NOT_FOUND -> {
                combat.cancelHunt();
                finish(Result.failed(t.id, Reason.ITEM_NOT_FOUND, new Result.FightDetail(0)));
            }
            case DONE_OK -> {
                int killed = combat.huntKilled();
                combat.cancelHunt();
                finish(Result.ok(t.id, new Result.FightDetail(killed)));
            }
            case TIMEOUT -> {
                int killed = combat.huntKilled();
                combat.cancelHunt();
                finish(Result.failed(t.id, Reason.TIMEOUT, new Result.FightDetail(killed)));
            }
            case GAVE_UP -> {
                int killed = combat.huntKilled();
                combat.cancelHunt();
                finish(Result.failed(t.id, Reason.STUCK, new Result.FightDetail(killed)));
            }
            case NONE -> finish(Result.failed(t.id, Reason.STUCK, new Result.FightDetail(combat.huntKilled())));
            default -> {
                // SEARCHING / FIGHTING：Combat 在干活
            }
        }
    }

    private void startSleep(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        BaritoneBridge.cancelEverything();
        send(MessageType.ACK, Ack.accepted(id));
        LocalPlayer me = mc.player;
        if (!Survival.isNight(mc.level.getDayTime())) {
            CompanionMod.LOG.info(CompanionMod.TAG + "sleep: not night (day_time={}) -> not_night", mc.level.getDayTime() % 24000);
            finish(Result.failed(id, Reason.NOT_NIGHT, new Result.SleepDetail(false)));
            return;
        }
        Task t = new Task(id, CommandName.SLEEP, args, tick);
        BlockPos bed = findFreeBed(mc, me.blockPosition(), STATION_RADIUS);
        if (bed != null) {
            t.stationPos = bed;
            t.selfPlaced = false;
            t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
            IBaritone b = BaritoneBridge.baritone();
            if (me.position().distanceTo(Vec3.atCenterOf(bed)) > 3.0 && b != null) {
                setGoal(b, t, bed, 2);
                t.phase = Phase.STATION_APPROACH;
            } else {
                t.phase = Phase.STATION_OPEN;
                t.openSince = -1;
            }
        } else {
            String ownBed = null;
            for (ItemStack st : me.getInventory().items) {
                String idStr = idOf(st);
                if (idStr != null && idStr.endsWith("_bed")) {
                    ownBed = idStr;
                    break;
                }
            }
            if (ownBed == null) {
                CompanionMod.LOG.info(CompanionMod.TAG + "sleep: no bed within {} and none in inventory -> no_bed", STATION_RADIUS);
                finish(Result.failed(id, Reason.NO_BED, new Result.SleepDetail(false)));
                return;
            }
            int rc = beginPlaceOp(t, mc, me, ownBed);
            if (rc != 0) {
                CompanionMod.LOG.info(CompanionMod.TAG + "sleep: cannot place own bed (rc={}) -> stuck", rc);
                finish(Result.failed(id, Reason.STUCK, new Result.SleepDetail(false)));
                return;
            }
            t.selfPlaced = true;
            t.phase = Phase.STATION_PLACE;
        }
        current = t;
        CompanionMod.LOG.info(CompanionMod.TAG + "sleep started bed={} self_placed={}", t.stationPos, t.selfPlaced);
    }

    private void tickSleep(Minecraft mc, LocalPlayer me) {
        Task t = current;
        if (tickStation(t, mc, me, p -> p.isSleeping())) {
            BlockPos bed = me.getSleepingPos().orElse(t.stationPos);
            if (t.selfPlaced) {
                bedToCollect = bed;
                bedToCollectId = t.opBlockId;
            }
            CompanionMod.LOG.info(CompanionMod.TAG + "sleep: lying in bed at {} (self_placed={})", bed, t.selfPlaced);
            finish(Result.ok(t.id, new Result.SleepDetail(true, new Pos(bed.getX(), bed.getY(), bed.getZ()))));
        }
    }

    /** 8 格内没人占的床（床头那格）；没有返回 null。 */
    static BlockPos findFreeBed(Minecraft mc, BlockPos center, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    var st = mc.level.getBlockState(p.set(x, y, z));
                    if (!(st.getBlock() instanceof BedBlock) || st.getValue(BedBlock.PART) != BedPart.HEAD || st.getValue(BedBlock.OCCUPIED)) {
                        continue;
                    }
                    double d = center.distSqr(p);
                    if (d < bestD) {
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    // ---------------- 任务单 14：craft / place / pickup_block / use_furnace ----------------

    private void startCraft(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        BaritoneBridge.cancelEverything();
        send(MessageType.ACK, Ack.accepted(id));
        Command.CraftArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.CraftArgs.class);
        String item = normalizeItem(a == null ? null : a.item());
        int wanted = a == null ? 1 : Math.max(1, a.countOrDefault());
        if (item == null || mc.level == null || mc.gameMode == null) {
            finish(Result.failed(id, Reason.NO_RECIPE, new Result.CraftDetail(0)));
            return;
        }
        LocalPlayer me = mc.player;
        if (me.containerMenu != me.inventoryMenu) {
            me.closeContainer();
        }
        if (!me.inventoryMenu.getCarried().isEmpty()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: cursor not empty -> stuck");
            finish(Result.failed(id, Reason.STUCK, new Result.CraftDetail(0)));
            return;
        }
        List<CraftingRecipe> all = new ArrayList<>();
        for (CraftingRecipe r : mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack out = r.getResultItem(mc.level.registryAccess());
            if (out != null && !out.isEmpty() && item.equals(idOf(out))) {
                all.add(r);
            }
        }
        if (all.isEmpty()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: no recipe for {}", item);
            finish(Result.failed(id, Reason.NO_RECIPE, new Result.CraftDetail(0)));
            return;
        }
        Map<String, Integer> inv = inventorySummary(me);
        // 先试 2×2（背包自带的格子），再试 3×3（要工作台）
        CraftingRecipe chosen = null;
        List<CraftPlanner.Cell> chosenCells = null;
        boolean useTable = false;
        Map<String, Integer> firstMissing = null;
        for (int pass = 0; pass < 2 && chosen == null; pass++) {
            int w = pass == 0 ? 2 : 3;
            for (CraftingRecipe r : all) {
                if (pass == 0 && !r.canCraftInDimensions(2, 2)) {
                    continue;
                }
                List<CraftPlanner.Cell> cells = cellsFor(r, w, w);
                if (cells == null) {
                    continue;
                }
                CraftPlanner.Plan plan = CraftPlanner.plan(cells, inv);
                if (plan.ok()) {
                    chosen = r;
                    chosenCells = cells;
                    useTable = pass == 1;
                    break;
                }
                if (firstMissing == null) {
                    firstMissing = plan.missing();
                }
            }
        }
        if (chosen == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: missing {} for {}", firstMissing, item);
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, firstMissing == null ? Map.of() : firstMissing)));
            return;
        }
        Task t = new Task(id, CommandName.CRAFT, args, tick);
        t.item = item;
        t.cells = chosenCells;
        t.perCraft = Math.max(1, chosen.getResultItem(mc.level.registryAccess()).getCount());
        t.batchesLeft = CraftPlanner.batches(wanted, t.perCraft);
        t.startCount = countItem(me, item);
        t.batchStartCount = t.startCount;
        t.useTable = useTable;
        if (!useTable) {
            t.clicks = buildClicks(me, CraftPlanner.plan(chosenCells, inv), 1, ContainerSlots.INVENTORY_MENU_START);
            current = t;
            CompanionMod.LOG.info(CompanionMod.TAG + "craft started item={} wanted={} recipe={} per_craft={} batches={} grid=2x2", item, wanted,
                    chosen.getId(), t.perCraft, t.batchesLeft);
            return;
        }
        // 3×3：找工作台，没有就放一个
        if (!beginStation(t, mc, me, "minecraft:crafting_table", Blocks.CRAFTING_TABLE)) {
            if (t.opReason == Reason.NO_MATERIALS) {
                finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of("minecraft:crafting_table", 1))));
            } else {
                finish(Result.failed(id, t.opReason == null ? Reason.STUCK : t.opReason, new Result.CraftDetail(0)));
            }
            return;
        }
        current = t;
        CompanionMod.LOG.info(CompanionMod.TAG + "craft started item={} wanted={} recipe={} per_craft={} batches={} grid=3x3 station={} self_placed={}",
                item, wanted, chosen.getId(), t.perCraft, t.batchesLeft, t.stationPos, t.selfPlaced);
    }

    /** 配方的每个格子能放什么，铺到 gridW×gridH 里；铺不下返回 null。 */
    static List<CraftPlanner.Cell> cellsFor(CraftingRecipe r, int gridW, int gridH) {
        List<CraftPlanner.Cell> cells = new ArrayList<>();
        for (Ingredient ing : r.getIngredients()) {
            Set<String> accepts = new LinkedHashSet<>();
            if (!ing.isEmpty()) {
                for (ItemStack st : ing.getItems()) {
                    String id = idOf(st);
                    if (id != null) {
                        accepts.add(id);
                    }
                }
            }
            cells.add(new CraftPlanner.Cell(accepts));
        }
        if (r instanceof ShapedRecipe sr) {
            return CraftPlanner.layout(cells, sr.getWidth(), sr.getHeight(), gridW, gridH);
        }
        List<CraftPlanner.Cell> nonEmpty = cells.stream().filter(c -> !c.empty()).toList();
        return CraftPlanner.layoutShapeless(nonEmpty, gridW, gridH);
    }

    /** 主背包 36 格的物品摘要（不含副手、盔甲）。 */
    static Map<String, Integer> inventorySummary(LocalPlayer me) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack st : me.getInventory().items) {
            String id = idOf(st);
            if (id != null) {
                m.merge(id, st.getCount(), Integer::sum);
            }
        }
        return m;
    }

    /**
     * 把分配好的材料一格一格放进合成格（gridBase 起），最后 shift 点结果格（0）。
     * 每个格子三下：拿起材料叠 → 右键放一个 → 把剩下的放回去。invStart 是这个菜单里主背包的起始格。
     */
    private static ArrayDeque<Click> buildClicks(LocalPlayer me, CraftPlanner.Plan plan, int gridBase, int invStart) {
        ArrayDeque<Click> q = new ArrayDeque<>();
        int[] left = new int[36];
        var items = me.getInventory().items;
        for (int i = 0; i < 36; i++) {
            left[i] = items.get(i).getCount();
        }
        for (int g = 0; g < plan.assignment().size(); g++) {
            String want = plan.assignment().get(g);
            if (want == null) {
                continue;
            }
            int src = -1;
            for (int i = 0; i < 36; i++) {
                if (left[i] > 0 && want.equals(idOf(items.get(i)))) {
                    src = i;
                    break;
                }
            }
            if (src < 0) {
                return new ArrayDeque<>(); // 不该发生：plan 刚算过材料够
            }
            left[src]--;
            int srcSlot = ContainerSlots.slotFor(src, invStart);
            q.add(new Click(srcSlot, 0, ClickType.PICKUP));
            q.add(new Click(gridBase + g, 1, ClickType.PICKUP));
            q.add(new Click(srcSlot, 0, ClickType.PICKUP));
        }
        q.add(new Click(0, 0, ClickType.QUICK_MOVE));
        return q;
    }

    /**
     * 合成的点击推进：一 tick 一下；点完等成品进背包；多份就重算再摆。
     * 返回 null = 还在做；返回 Result = 做完了（ok 或 failed），由调用方决定怎么收尾。
     */
    private Result stepCraft(Task t, Minecraft mc, LocalPlayer me, int containerId, int gridBase, int invStart) {
        if (!t.clicks.isEmpty()) {
            Click c = t.clicks.poll();
            mc.gameMode.handleInventoryMouseClick(containerId, c.slot(), c.button(), c.type(), me);
            if (t.clicks.isEmpty()) {
                t.waitSince = tick;
            }
            return null;
        }
        int now = countItem(me, t.item);
        if (now >= t.batchStartCount + t.perCraft) {
            t.batchesLeft--;
            send(MessageType.PROGRESS, new Progress(t.id, "crafted", (double) (now - t.startCount), Observer.pos(me)));
            if (t.batchesLeft <= 0) {
                return Result.ok(t.id, new Result.CraftDetail(now - t.startCount));
            }
            CraftPlanner.Plan plan = CraftPlanner.plan(t.cells, inventorySummary(me));
            if (!plan.ok()) {
                CompanionMod.LOG.info(CompanionMod.TAG + "craft: ran out after {} ({})", now - t.startCount, plan.missing());
                return Result.failed(t.id, Reason.NO_MATERIALS, new Result.CraftDetail(now - t.startCount, plan.missing()));
            }
            t.batchStartCount = now;
            t.clicks = buildClicks(me, plan, gridBase, invStart);
            t.waitSince = -1;
            return null;
        }
        if (t.waitSince >= 0 && tick - t.waitSince >= CRAFT_RESULT_WAIT_TICKS) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: result never arrived (have {} want {}) -> stuck", now, t.batchStartCount + t.perCraft);
            return Result.failed(t.id, Reason.STUCK, new Result.CraftDetail(now - t.startCount));
        }
        return null;
    }

    private void tickCraft(Minecraft mc, LocalPlayer me) {
        Task t = current;
        if (!t.useTable) {
            Result r = stepCraft(t, mc, me, me.inventoryMenu.containerId, 1, ContainerSlots.INVENTORY_MENU_START);
            if (r != null) {
                finish(r);
            }
            return;
        }
        switch (t.phase) {
            case STATION_PLACE, STATION_APPROACH, STATION_OPEN -> {
                if (tickStation(t, mc, me, CraftingMenu.class)) {
                    t.clicks = buildClicks(me, CraftPlanner.plan(t.cells, inventorySummary(me)), 1, ContainerSlots.CRAFTING_MENU_START);
                    t.batchStartCount = countItem(me, t.item);
                    t.waitSince = -1;
                    t.phase = Phase.TABLE_CRAFT;
                }
            }
            case TABLE_CRAFT -> {
                if (!(me.containerMenu instanceof CraftingMenu)) {
                    CompanionMod.LOG.info(CompanionMod.TAG + "craft: crafting menu closed under us -> stuck");
                    endStation(t, mc, me, Result.failed(t.id, Reason.STUCK, new Result.CraftDetail(countItem(me, t.item) - t.startCount)));
                    return;
                }
                Result r = stepCraft(t, mc, me, me.containerMenu.containerId, 1, ContainerSlots.CRAFTING_MENU_START);
                if (r != null) {
                    endStation(t, mc, me, r);
                }
            }
            case STATION_PICKUP -> tickStationPickup(t, mc, me);
            default -> {
            }
        }
    }

    // ---- 工作台 / 熔炉共用：找、放、走过去、打开、收回 ----

    /**
     * 找 8 格内的 station（工作台 / 熔炉）；没有但背包有就放一个（记为自己放的）。
     * 返回 false 表示没法开始，原因在 t.opReason（NO_MATERIALS = 没有也没带；其他 = 放不下）。
     */
    private boolean beginStation(Task t, Minecraft mc, LocalPlayer me, String blockId, Block block) {
        BlockPos found = findNearestBlock(mc, me.blockPosition(), block, STATION_RADIUS);
        if (found != null) {
            t.stationPos = found;
            t.selfPlaced = false;
            double dist = me.position().distanceTo(Vec3.atCenterOf(found));
            IBaritone b = BaritoneBridge.baritone();
            if (dist > 3.5 && b != null) {
                t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
                setGoal(b, t, found, 2);
                t.phase = Phase.STATION_APPROACH;
            } else {
                t.phase = Phase.STATION_OPEN;
                t.openSince = -1;
            }
            return true;
        }
        if (findInventoryIndex(me, blockId) < 0) {
            t.opReason = Reason.NO_MATERIALS;
            return false;
        }
        int rc = beginPlaceOp(t, mc, me, blockId);
        if (rc != 0) {
            t.opReason = Reason.STUCK;
            return false;
        }
        t.selfPlaced = true;
        t.phase = Phase.STATION_PLACE;
        return true;
    }

    /** 推进 放 → 走过去 → 打开菜单；菜单打开（类型是 menuType）返回 true；失败自己收尾。 */
    private boolean tickStation(Task t, Minecraft mc, LocalPlayer me, Class<?> menuType) {
        return tickStation(t, mc, me, p -> menuType.isInstance(p.containerMenu));
    }

    /** 同上，"打开了"由 opened 判定（睡觉用 isSleeping）。 */
    private boolean tickStation(Task t, Minecraft mc, LocalPlayer me, java.util.function.Predicate<LocalPlayer> opened) {
        IBaritone b = BaritoneBridge.baritone();
        switch (t.phase) {
            case STATION_PLACE -> {
                OpState st = tickPlaceOp(t, mc, me);
                if (st == OpState.DONE) {
                    t.stationPos = t.opPos;
                    placedBlocks.put(t.opBlockId, t.opPos);
                    t.phase = Phase.STATION_OPEN;
                    t.openSince = -1;
                } else if (st == OpState.FAILED) {
                    finish(Result.failed(t.id, t.opReason == null ? Reason.STUCK : t.opReason));
                }
            }
            case STATION_APPROACH -> {
                double dist = me.position().distanceTo(Vec3.atCenterOf(t.stationPos));
                if (dist <= 3.5) {
                    BaritoneBridge.cancelEverything();
                    t.phase = Phase.STATION_OPEN;
                    t.openSince = -1;
                    return false;
                }
                if (t.pathFailed) {
                    BaritoneBridge.cancelEverything();
                    finish(Result.failed(t.id, Reason.PATH_FAILED));
                    return false;
                }
                if (t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick)) {
                    BaritoneBridge.cancelEverything();
                    finish(Result.failed(t.id, Reason.STUCK));
                    return false;
                }
                if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS
                        && !b.getPathingBehavior().isPathing() && !b.getCustomGoalProcess().isActive()) {
                    setGoal(b, t, t.stationPos, 2);
                }
            }
            case STATION_OPEN -> {
                if (t.openSince < 0) {
                    useBlock(mc, me, t.stationPos);
                    t.openSince = tick;
                    return false;
                }
                if (opened.test(me)) {
                    CompanionMod.LOG.info(CompanionMod.TAG + "station ready at {} ({})", t.stationPos, me.containerMenu.getClass().getSimpleName());
                    return true;
                }
                if (tick - t.openSince >= (t.name == CommandName.SLEEP ? SLEEP_CONFIRM_TICKS : MENU_OPEN_TIMEOUT_TICKS)) {
                    CompanionMod.LOG.info(CompanionMod.TAG + "station: nothing happened at {} after use -> stuck (sleep: 附近有怪或床被占)", t.stationPos);
                    finish(Result.failed(t.id, Reason.STUCK));
                }
            }
            default -> {
            }
        }
        return false;
    }

    /** 关菜单；自己放的 station 先挖回来再回结果。 */
    private void endStation(Task t, Minecraft mc, LocalPlayer me, Result r) {
        me.closeContainer();
        if (t.selfPlaced && t.stationPos != null) {
            t.pending = r;
            Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(t.opBlockId));
            beginBreakOp(t, mc, me, t.opBlockId, block, t.stationPos);
            t.phase = Phase.STATION_PICKUP;
            return;
        }
        finish(r);
    }

    private void tickStationPickup(Task t, Minecraft mc, LocalPlayer me) {
        OpState st = tickBreakOp(t, mc, me);
        if (st == OpState.RUNNING) {
            return;
        }
        if (st == OpState.FAILED) {
            CompanionMod.LOG.info(CompanionMod.TAG + "station: could not pick {} back up ({}), leaving it recorded", t.opBlockId, t.opReason);
        } else {
            placedBlocks.remove(t.opBlockId);
        }
        finish(t.pending);
    }

    /** 对一个方块"右键"（打开工作台 / 熔炉）：从伙伴这一侧的面点进去。 */
    private static void useBlock(Minecraft mc, LocalPlayer me, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = Direction.getNearest(me.getX() - center.x, me.getEyeY() - center.y, me.getZ() - center.z);
        Vec3 hit = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        var res = mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        me.swing(InteractionHand.MAIN_HAND);
        CompanionMod.LOG.info(CompanionMod.TAG + "useBlock {} face={} -> {}", pos, face, res);
    }

    static BlockPos findNearestBlock(Minecraft mc, BlockPos center, Block block, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    if (mc.level.getBlockState(p.set(x, y, z)).is(block)) {
                        double d = center.distSqr(p);
                        if (d < bestD) {
                            bestD = d;
                            best = p.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    // ---- 子动作：放一个方块 ----

    /** 0 = 开始了；1 = 背包里没有；2 = 脚边没空位。 */
    private int beginPlaceOp(Task t, Minecraft mc, LocalPlayer me, String blockId) {
        int src = findInventoryIndex(me, blockId);
        if (src < 0) {
            return 1;
        }
        BlockPos feet = me.blockPosition();
        PlaceSpot.Cell spot = PlaceSpot.pick(new PlaceSpot.Cell(feet.getX(), feet.getY(), feet.getZ()),
                c -> {
                    var st = mc.level.getBlockState(new BlockPos(c.x(), c.y(), c.z()));
                    return st.isAir() || st.canBeReplaced();
                },
                c -> {
                    BlockPos bp = new BlockPos(c.x(), c.y(), c.z());
                    return mc.level.getBlockState(bp).isFaceSturdy(mc.level, bp, Direction.UP);
                }).orElse(null);
        if (spot == null) {
            return 2;
        }
        t.opBlockId = blockId;
        t.opPos = new BlockPos(spot.x(), spot.y(), spot.z());
        if (src < 9) {
            me.getInventory().selected = src;
        } else {
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, ContainerSlots.slotFor(src, ContainerSlots.INVENTORY_MENU_START),
                    PLACE_HOTBAR_SLOT, ClickType.SWAP, me);
            me.getInventory().selected = PLACE_HOTBAR_SLOT;
        }
        t.opStage = 1;
        t.opStartTick = tick;
        CompanionMod.LOG.info(CompanionMod.TAG + "place op: {} at {} from slot {}", blockId, t.opPos, src);
        return 0;
    }

    private OpState tickPlaceOp(Task t, Minecraft mc, LocalPlayer me) {
        if (t.opStage == 1) {
            if (tick - t.opStartTick < PLACE_SETTLE_TICKS) {
                return OpState.RUNNING;
            }
            ItemStack held = me.getMainHandItem();
            if (!t.opBlockId.equals(idOf(held))) {
                CompanionMod.LOG.info(CompanionMod.TAG + "place op: hand holds {} not {} -> stuck", idOf(held), t.opBlockId);
                t.opReason = Reason.STUCK;
                return OpState.FAILED;
            }
            BlockPos below = t.opPos.below();
            Vec3 hit = new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
            BreakGuard.allowPlace("place " + t.opBlockId);
            var res = mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
            me.swing(InteractionHand.MAIN_HAND);
            CompanionMod.LOG.info(CompanionMod.TAG + "place op: useItemOn -> {}", res);
            t.opStage = 2;
            t.opStartTick = tick;
            return OpState.RUNNING;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(t.opBlockId));
        if (block != null && mc.level.getBlockState(t.opPos).is(block)) {
            BreakGuard.closeAll("placed " + t.opBlockId);
            return OpState.DONE;
        }
        if (tick - t.opStartTick >= PLACE_CONFIRM_TICKS) {
            CompanionMod.LOG.info(CompanionMod.TAG + "place op: block did not appear at {} -> stuck", t.opPos);
            BreakGuard.closeAll("place failed");
            t.opReason = Reason.STUCK;
            return OpState.FAILED;
        }
        return OpState.RUNNING;
    }

    // ---- 子动作：把自己放的方块挖回来 ----

    private void beginBreakOp(Task t, Minecraft mc, LocalPlayer me, String blockId, Block block, BlockPos pos) {
        t.opBlockId = blockId;
        t.opBlock = block;
        t.opPos = pos;
        t.opStartCount = countItem(me, blockId);
        t.opBreaking = false;
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        t.opStartTick = tick;
        double dist = me.position().distanceTo(Vec3.atCenterOf(pos));
        IBaritone b = BaritoneBridge.baritone();
        if (dist > 4.0 && b != null) {
            t.opStage = 0; // 走过去
            setGoal(b, t, pos, 2);
        } else {
            t.opStage = 1; // 挖
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "break op: {} at {} dist={}", blockId, pos, round(dist));
    }

    private OpState tickBreakOp(Task t, Minecraft mc, LocalPlayer me) {
        IBaritone b = BaritoneBridge.baritone();
        switch (t.opStage) {
            case 0 -> {
                double dist = me.position().distanceTo(Vec3.atCenterOf(t.opPos));
                if (dist <= 3.5) {
                    BaritoneBridge.cancelEverything();
                    t.opStage = 1;
                    t.opStartTick = tick;
                    return OpState.RUNNING;
                }
                if (t.pathFailed) {
                    BaritoneBridge.cancelEverything();
                    t.opReason = Reason.PATH_FAILED;
                    return OpState.FAILED;
                }
                if (t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick)) {
                    BaritoneBridge.cancelEverything();
                    t.opReason = Reason.STUCK;
                    return OpState.FAILED;
                }
                if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS
                        && !b.getPathingBehavior().isPathing() && !b.getCustomGoalProcess().isActive()) {
                    setGoal(b, t, t.opPos, 2);
                }
            }
            case 1 -> {
                if (mc.level.getBlockState(t.opPos).isAir()) {
                    mc.gameMode.stopDestroyBlock();
                    t.opBreaking = false;
                    t.opStage = 2;
                    t.opStartTick = tick;
                    if (b != null) {
                        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(t.opPos, 0)); // 走过去把掉落捡起来
                    }
                    return OpState.RUNNING;
                }
                if (!t.opBreaking) {
                    int pick = findPickaxe(me);
                    if (pick >= 0) {
                        equip(mc, me, pick); // 工作台徒手也行，熔炉没镐挖不掉；有镐就拿着，快得多
                    } else if (rules.needsPickaxe(t.opBlockId)) {
                        CompanionMod.LOG.info(CompanionMod.TAG + "break op: {} needs a pickaxe and there is none -> stuck", t.opBlockId);
                        t.opReason = Reason.STUCK;
                        return OpState.FAILED;
                    }
                    BreakGuard.allowBreakOnly(List.of(t.opBlock), "break op " + t.opBlockId);
                    mc.gameMode.startDestroyBlock(t.opPos, Direction.UP);
                    t.opBreaking = true;
                }
                mc.gameMode.continueDestroyBlock(t.opPos, Direction.UP);
                me.swing(InteractionHand.MAIN_HAND);
                if (tick - t.opStartTick >= BREAK_TIMEOUT_TICKS) {
                    mc.gameMode.stopDestroyBlock();
                    CompanionMod.LOG.info(CompanionMod.TAG + "break op: still not broken after {} ticks -> stuck", BREAK_TIMEOUT_TICKS);
                    t.opReason = Reason.STUCK;
                    return OpState.FAILED;
                }
            }
            case 2 -> {
                if (countItem(me, t.opBlockId) > t.opStartCount) {
                    BaritoneBridge.cancelEverything();
                    return OpState.DONE;
                }
                if (tick - t.opStartTick >= PICK_TIMEOUT_TICKS) {
                    BaritoneBridge.cancelEverything();
                    t.opReason = Reason.NOT_PICKED;
                    return OpState.FAILED;
                }
            }
            default -> {
            }
        }
        return OpState.RUNNING;
    }

    // ---- place / pickup_block 命令（第一批）：套用上面两个子动作 ----

    private void startPlace(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        BaritoneBridge.cancelEverything();
        send(MessageType.ACK, Ack.accepted(id));
        Command.PlaceArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.PlaceArgs.class);
        String blockId = a == null || a.block() == null ? "" : Rules.normalize(a.block());
        if (!rules.canPlace(blockId)) {
            CompanionMod.LOG.info(CompanionMod.TAG + "place: {} not placeable (received={})", blockId, rules.received());
            finish(Result.failed(id, Reason.NOT_ALLOWED));
            return;
        }
        Task t = new Task(id, CommandName.PLACE, args, tick);
        int rc = beginPlaceOp(t, mc, mc.player, blockId);
        if (rc == 1) {
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of(blockId, 1))));
            return;
        }
        if (rc == 2) {
            CompanionMod.LOG.info(CompanionMod.TAG + "place: no free spot next to feet -> stuck");
            finish(Result.failed(id, Reason.STUCK));
            return;
        }
        current = t;
    }

    private void tickPlace(Minecraft mc, LocalPlayer me) {
        Task t = current;
        OpState st = tickPlaceOp(t, mc, me);
        if (st == OpState.DONE) {
            placedBlocks.put(t.opBlockId, t.opPos);
            finish(Result.ok(t.id, new Result.PlaceDetail(new Pos(t.opPos.getX(), t.opPos.getY(), t.opPos.getZ()))));
        } else if (st == OpState.FAILED) {
            finish(Result.failed(t.id, t.opReason == null ? Reason.STUCK : t.opReason));
        }
    }

    private void startPickupBlock(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        send(MessageType.ACK, Ack.accepted(id));
        Command.PickupBlockArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.PickupBlockArgs.class);
        String blockId = a == null || a.block() == null || a.block().isBlank() ? "minecraft:crafting_table" : Rules.normalize(a.block());
        BlockPos pos = placedBlocks.get(blockId);
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(blockId));
        if (pos == null || block == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "pickup_block: never placed {} -> item_not_found", blockId);
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND));
            return;
        }
        if (!mc.level.getBlockState(pos).is(block)) {
            CompanionMod.LOG.info(CompanionMod.TAG + "pickup_block: {} at {} is gone -> item_not_found", blockId, pos);
            placedBlocks.remove(blockId);
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND));
            return;
        }
        Task t = new Task(id, CommandName.PICKUP_BLOCK, args, tick);
        beginBreakOp(t, mc, mc.player, blockId, block, pos);
        current = t;
    }

    private void tickPickupBlock(Minecraft mc, LocalPlayer me) {
        Task t = current;
        OpState st = tickBreakOp(t, mc, me);
        if (st == OpState.DONE) {
            placedBlocks.remove(t.opBlockId);
            finish(Result.ok(t.id, new Result.PlaceDetail(new Pos(t.opPos.getX(), t.opPos.getY(), t.opPos.getZ()))));
        } else if (st == OpState.FAILED) {
            if (t.opReason == Reason.NOT_PICKED) {
                placedBlocks.remove(t.opBlockId);
            }
            finish(Result.failed(t.id, t.opReason == null ? Reason.STUCK : t.opReason));
        }
    }

    // ---- use_furnace（第二批） ----

    private void startUseFurnace(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        BaritoneBridge.cancelEverything();
        send(MessageType.ACK, Ack.accepted(id));
        Command.UseFurnaceArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.UseFurnaceArgs.class);
        LocalPlayer me = mc.player;
        String input = normalizeItem(a == null ? null : a.input());
        Map<String, Integer> inv = inventorySummary(me);
        if (input == null || inv.getOrDefault(input, 0) <= 0) {
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of(input == null ? "input" : input, 1))));
            return;
        }
        String requested = a == null ? null : normalizeItem(a.fuel());
        String fuel = FurnaceLoader.pickFuel(inv, requested).orElse(null);
        if (fuel == null) {
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of(requested == null ? "minecraft:coal" : requested, 1))));
            return;
        }
        if (me.containerMenu != me.inventoryMenu) {
            me.closeContainer();
        }
        Task t = new Task(id, CommandName.USE_FURNACE, args, tick);
        t.item = input;
        t.fuelId = fuel;
        t.smeltCount = a == null ? null : a.count();
        if (!beginStation(t, mc, me, "minecraft:furnace", Blocks.FURNACE)) {
            if (t.opReason == Reason.NO_MATERIALS) {
                CompanionMod.LOG.info(CompanionMod.TAG + "use_furnace: no furnace nearby and none in inventory -> item_not_found");
                finish(Result.failed(id, Reason.ITEM_NOT_FOUND, new Result.UseFurnaceDetail(0)));
            } else {
                finish(Result.failed(id, Reason.STUCK, new Result.UseFurnaceDetail(0)));
            }
            return;
        }
        current = t;
        CompanionMod.LOG.info(CompanionMod.TAG + "use_furnace started input={} fuel={} count={} station={} self_placed={}", input, fuel,
                t.smeltCount, t.stationPos, t.selfPlaced);
    }

    /** 装料：原料（全部或 count 个）进格 0，燃料一叠进格 1。 */
    private ArrayDeque<Click> buildFurnaceLoad(LocalPlayer me, Task t) {
        ArrayDeque<Click> q = new ArrayDeque<>();
        var items = me.getInventory().items;
        int invStart = ContainerSlots.FURNACE_MENU_START;
        if (t.smeltCount == null) {
            for (int i = 0; i < 36; i++) {
                if (t.item.equals(idOf(items.get(i)))) {
                    q.add(new Click(ContainerSlots.slotFor(i, invStart), 0, ClickType.QUICK_MOVE));
                }
            }
        } else {
            int need = Math.max(1, t.smeltCount);
            for (int i = 0; i < 36 && need > 0; i++) {
                ItemStack st = items.get(i);
                if (!t.item.equals(idOf(st))) {
                    continue;
                }
                int take = Math.min(need, st.getCount());
                int slot = ContainerSlots.slotFor(i, invStart);
                q.add(new Click(slot, 0, ClickType.PICKUP));
                for (int k = 0; k < take; k++) {
                    q.add(new Click(AbstractFurnaceMenu.INGREDIENT_SLOT, 1, ClickType.PICKUP));
                }
                q.add(new Click(slot, 0, ClickType.PICKUP));
                need -= take;
            }
        }
        for (int i = 0; i < 36; i++) {
            if (t.fuelId.equals(idOf(items.get(i)))) {
                q.add(new Click(ContainerSlots.slotFor(i, invStart), 0, ClickType.QUICK_MOVE));
                break;
            }
        }
        return q;
    }

    private void tickUseFurnace(Minecraft mc, LocalPlayer me) {
        Task t = current;
        switch (t.phase) {
            case STATION_PLACE, STATION_APPROACH, STATION_OPEN -> {
                if (tickStation(t, mc, me, AbstractFurnaceMenu.class)) {
                    t.clicks = buildFurnaceLoad(me, t);
                    t.phase = Phase.FURNACE_LOAD;
                    CompanionMod.LOG.info(CompanionMod.TAG + "use_furnace: loading with {} clicks", t.clicks.size());
                }
            }
            case FURNACE_LOAD -> {
                if (!(me.containerMenu instanceof AbstractFurnaceMenu)) {
                    endStation(t, mc, me, Result.failed(t.id, Reason.STUCK, new Result.UseFurnaceDetail(t.smelted)));
                    return;
                }
                if (!t.clicks.isEmpty()) {
                    Click c = t.clicks.poll();
                    mc.gameMode.handleInventoryMouseClick(me.containerMenu.containerId, c.slot(), c.button(), c.type(), me);
                    return;
                }
                t.phase = Phase.FURNACE_WAIT;
                t.lastCheckTick = tick;
                t.unlitSince = -1;
            }
            case FURNACE_WAIT -> {
                if (!(me.containerMenu instanceof AbstractFurnaceMenu menu)) {
                    endStation(t, mc, me, Result.failed(t.id, Reason.STUCK, new Result.UseFurnaceDetail(t.smelted)));
                    return;
                }
                if (tick - t.lastCheckTick < FURNACE_CHECK_TICKS) {
                    return;
                }
                t.lastCheckTick = tick;
                ItemStack result = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
                if (!result.isEmpty()) {
                    t.smelted += result.getCount();
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, me);
                    send(MessageType.PROGRESS, new Progress(t.id, "smelted", (double) t.smelted, Observer.pos(me)));
                    return;
                }
                ItemStack inputLeft = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
                if (inputLeft.isEmpty()) {
                    CompanionMod.LOG.info(CompanionMod.TAG + "use_furnace: done, smelted {}", t.smelted);
                    endStation(t, mc, me, Result.ok(t.id, new Result.UseFurnaceDetail(t.smelted)));
                    return;
                }
                if (!menu.isLit()) {
                    if (t.unlitSince < 0) {
                        t.unlitSince = tick;
                    } else if (tick - t.unlitSince >= FURNACE_UNLIT_TICKS && menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()) {
                        CompanionMod.LOG.info(CompanionMod.TAG + "use_furnace: fuel ran out with {} left, smelted {}", inputLeft.getCount(), t.smelted);
                        endStation(t, mc, me, Result.failed(t.id, Reason.NO_MATERIALS, new Result.UseFurnaceDetail(t.smelted)));
                    }
                } else {
                    t.unlitSince = -1;
                }
            }
            case STATION_PICKUP -> tickStationPickup(t, mc, me);
            default -> {
            }
        }
    }

    /** 背包里最好的镐（木 < 石 < 铁 < 钻 < 下界合金），没有返回 -1。 */
    static int findPickaxe(LocalPlayer me) {
        String[] order = {"minecraft:netherite_pickaxe", "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:golden_pickaxe",
                "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe"};
        for (String id : order) {
            int i = findInventoryIndex(me, id);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** 把背包第 src 格拿到手上（在快捷栏就选中，不在就换到快捷栏最后一格）。 */
    private static void equip(Minecraft mc, LocalPlayer me, int src) {
        if (src < 9) {
            me.getInventory().selected = src;
        } else {
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, ContainerSlots.slotFor(src, ContainerSlots.INVENTORY_MENU_START),
                    PLACE_HOTBAR_SLOT, ClickType.SWAP, me);
            me.getInventory().selected = PLACE_HOTBAR_SLOT;
        }
    }

    /**
     * 走不出去（多半是挖矿时把自己挖进洞里了）：准许破坏整张可挖清单（都是自然方块，房子的东西不在里面）再找一次路。
     * 只试一次；返回 true 表示已经重新设目标。
     */
    private boolean digOutRetry(Task t, IBaritone b, BlockPos goal, int range) {
        if (t.dugOut || b == null) {
            return false;
        }
        t.dugOut = true;
        List<Block> allowed = new ArrayList<>();
        for (String idStr : rules.mineableIds()) {
            ResourceLocation r = ResourceLocation.tryParse(idStr);
            Block bl = r == null ? null : ForgeRegistries.BLOCKS.getValue(r);
            if (bl != null && bl != Blocks.AIR) {
                allowed.add(bl);
            }
        }
        if (allowed.isEmpty()) {
            return false;
        }
        int pick = findPickaxe(Minecraft.getInstance().player);
        if (pick >= 0) {
            equip(Minecraft.getInstance(), Minecraft.getInstance().player, pick);
        }
        BreakGuard.allowBreakOnly(allowed, "dig out: path failed");
        CompanionMod.LOG.info(CompanionMod.TAG + "path failed, retrying once with natural blocks breakable");
        setGoal(b, t, goal, range);
        return true;
    }

    /** 主背包里第一格有这种物品的序号（0–8 快捷栏），没有返回 -1。 */
    static int findInventoryIndex(LocalPlayer me, String item) {
        var items = me.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (item.equals(idOf(items.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    // ---------------- 任务单 13：goto / mine / collect ----------------

    /** goto 只认坐标；地点名由后台换成坐标再发来。 */
    private void startGoto(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        send(MessageType.ACK, Ack.accepted(id));
        if (args == null || !args.has("x") || !args.has("y") || !args.has("z")) {
            CompanionMod.LOG.info(CompanionMod.TAG + "goto: no coordinates -> no_place_known");
            finish(Result.failed(id, Reason.NO_PLACE_KNOWN));
            return;
        }
        Task t = new Task(id, CommandName.GOTO, args, tick);
        t.devGoal = new Pos(args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble());
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        current = t;
        setGoal(b, t, goalPos(null, t), 2);
        CompanionMod.LOG.info(CompanionMod.TAG + "goto started target={} place={}", t.devGoal,
                args.has("place") ? args.get("place").getAsString() : "-");
    }

    private void tickGoto(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        Vec3 target = new Vec3(t.devGoal.x(), t.devGoal.y(), t.devGoal.z());
        double dist = me.position().distanceTo(target);
        if (dist <= ARRIVE_DISTANCE) {
            BaritoneBridge.cancelEverything();
            finish(Result.ok(t.id, new Result.GotoDetail(round(dist))));
            return;
        }
        if (t.pathFailed) {
            if (digOutRetry(t, b, goalPos(null, t), 2)) {
                return;
            }
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.PATH_FAILED, new Result.GotoDetail(round(dist))));
            return;
        }
        if (t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick)) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.STUCK, new Result.GotoDetail(round(dist))));
            return;
        }
        if (tick - t.lastProgressTick >= MINE_PROGRESS_TICKS) {
            t.lastProgressTick = tick;
            send(MessageType.PROGRESS, new Progress(t.id, "distance", round(dist), Observer.pos(me)));
        }
        if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS) {
            boolean idle = !b.getPathingBehavior().isPathing() && !b.getCustomGoalProcess().isActive();
            if (idle) {
                setGoal(b, t, goalPos(null, t), 2);
            }
        }
    }

    // ---------------- 任务单 22：传送门与探路 ----------------

    private static PortalLogic.Cell cellOf(BlockPos p) {
        return new PortalLogic.Cell(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos posOf(PortalLogic.Cell c) {
        return new BlockPos(c.x(), c.y(), c.z());
    }

    private static Pos protoPos(PortalLogic.Cell c) {
        return new Pos(c.x(), c.y(), c.z());
    }

    private static PortalLogic.Probe portalProbe(Minecraft mc) {
        return new PortalLogic.Probe() {
            @Override
            public boolean isFree(int x, int y, int z) {
                var st = mc.level.getBlockState(new BlockPos(x, y, z));
                return st.isAir() || st.canBeReplaced();
            }

            @Override
            public boolean isSolidTop(int x, int y, int z) {
                BlockPos bp = new BlockPos(x, y, z);
                return mc.level.getBlockState(bp).isFaceSturdy(mc.level, bp, Direction.UP);
            }
        };
    }

    private static String pickScaffold(LocalPlayer me, int n) {
        for (String id : SCAFFOLD_CANDIDATES) {
            if (countItem(me, id) >= n) {
                return id;
            }
        }
        return null;
    }

    /** 站位：门框前方 2 格的两个候选里挑一个站得住的；都不行就原地。 */
    private static BlockPos pickStand(Minecraft mc, PortalLogic.Site site, BlockPos feet) {
        PortalLogic.Probe p = portalProbe(mc);
        for (PortalLogic.Cell c : PortalLogic.standingSpots(site)) {
            if (p.isFree(c.x(), c.y(), c.z()) && p.isFree(c.x(), c.y() + 1, c.z()) && p.isSolidTop(c.x(), c.y() - 1, c.z())) {
                return posOf(c);
            }
        }
        return feet;
    }

    /** 走到站位：到了（1.5 格内）或 20 秒没到就开始放。返回 true 表示还在走。 */
    private boolean walkingToStand(Task t, Minecraft mc, LocalPlayer me) {
        if (t.opStage != 0) {
            return false;
        }
        double dist = me.position().distanceTo(Vec3.atCenterOf(t.standPos));
        if (dist <= 1.5 || tick - t.opStartTick > PORTAL_WALK_TIMEOUT_TICKS) {
            BaritoneBridge.cancelEverything();
            t.opStage = 1;
            t.opStartTick = tick;
            t.waitSince = -1;
            CompanionMod.LOG.info(CompanionMod.TAG + "portal: at stand (dist {}), start", round(dist));
            return false;
        }
        IBaritone b = BaritoneBridge.baritone();
        if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS && !b.getPathingBehavior().isPathing()
                && !b.getCustomGoalProcess().isActive()) {
            setGoal(b, t, t.standPos, 1);
        }
        return true;
    }

    private void startBuildPortal(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        BaritoneBridge.cancelEverything();
        send(MessageType.ACK, Ack.accepted(id));
        Command.BuildPortalArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.BuildPortalArgs.class);
        if (a == null || a.block() == null || a.frame() == null || a.frame().isEmpty()) {
            finish(Result.failed(id, Reason.STUCK, new Result.BuildPortalDetail(null, 0, "no_blueprint")));
            return;
        }
        LocalPlayer me = mc.player;
        String block = Rules.normalize(a.block());
        int have = countItem(me, block);
        int need = a.frame().size();
        List<List<Integer>> corners = a.corners() == null ? List.of() : a.corners();
        List<List<Integer>> inside = a.inside() == null ? List.of() : a.inside();
        if (have < need) {
            CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: {} {} < {} -> no_materials", block, have, need);
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of(block, need - have))));
            return;
        }
        String scaffold = null;
        if (!corners.isEmpty() && have < need + corners.size()) {
            scaffold = pickScaffold(me, corners.size());
            if (scaffold == null) {
                CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: no corner blocks (need {} obsidian or scaffold) -> no_materials", corners.size());
                finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of("minecraft:cobblestone", corners.size()))));
                return;
            }
        }
        PortalLogic.Cell feet = cellOf(me.blockPosition());
        PortalLogic.Site site = PortalLogic.findSite(portalProbe(mc), feet, a.frame(), corners, inside).orElse(null);
        if (site == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: no flat 4x5 spot within 3 blocks -> stuck");
            finish(Result.failed(id, Reason.STUCK, new Result.BuildPortalDetail(null, 0, "no_flat_ground")));
            return;
        }
        Task t = new Task(id, CommandName.BUILD_PORTAL, args, tick);
        t.portalSite = site;
        t.portalBlock = block;
        t.scaffoldId = scaffold;
        t.frameCells = PortalLogic.absAll(site.origin(), a.frame(), site.axis());
        List<PortalLogic.Cell> cornerCells = PortalLogic.absAll(site.origin(), corners, site.axis());
        t.scaffoldCells = scaffold != null ? cornerCells : List.of();
        List<PortalLogic.Cell> all = new ArrayList<>(t.frameCells);
        all.addAll(cornerCells);
        t.portalPlan = PortalLogic.order(all, site.origin(), site.axis());
        t.portalInside = a.enter() != null ? PortalLogic.abs(site.origin(), a.enter(), site.axis())
                : inside.isEmpty() ? null : PortalLogic.abs(site.origin(), inside.get(0), site.axis());
        t.portalLight = a.light() != null ? PortalLogic.abs(site.origin(), a.light(), site.axis()) : null;
        t.standPos = pickStand(mc, site, me.blockPosition());
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        t.opStage = 0;
        t.opStartTick = tick;
        current = t;
        IBaritone b = BaritoneBridge.baritone();
        if (b != null) {
            setGoal(b, t, t.standPos, 1);
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: origin={} axis={} blocks={} scaffold={} stand={}", site.origin(), site.axis(),
                t.portalPlan.size(), scaffold, t.standPos);
    }

    private void tickBuildPortal(Minecraft mc, LocalPlayer me) {
        Task t = current;
        if (walkingToStand(t, mc, me)) {
            return;
        }
        if (t.opStage == 1) {
            if (t.portalIndex >= t.portalPlan.size()) {
                t.opStage = 2;
                t.scaffoldIndex = 0;
                t.opBreaking = false;
                t.opStartTick = tick;
                return;
            }
            PortalLogic.Placement pl = t.portalPlan.get(t.portalIndex);
            boolean isCorner = t.scaffoldCells.contains(pl.target());
            String item = isCorner ? t.scaffoldId : t.portalBlock;
            Block want = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(item));
            BlockPos tp = posOf(pl.target());
            if (want != null && mc.level.getBlockState(tp).is(want)) {
                if (!isCorner) {
                    t.portalPlaced++;
                }
                t.portalIndex++;
                t.portalRetry = 0;
                t.waitSince = -1;
                t.opStartTick = tick;
                CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: {} placed at {} ({}/{})", item, tp, t.portalIndex, t.portalPlan.size());
                return;
            }
            if (!PortalLogic.inReach(me.getX(), me.getEyeY(), me.getZ(), pl.target())) {
                IBaritone b = BaritoneBridge.baritone();
                if (t.waitSince != -3) {
                    t.waitSince = -3;
                    t.opStartTick = tick;
                    if (b != null) {
                        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(tp, 3));
                    }
                } else if (tick - t.opStartTick > PORTAL_REACH_TIMEOUT_TICKS) {
                    BaritoneBridge.cancelEverything();
                    BreakGuard.closeAll("portal unreachable");
                    finish(Result.failed(t.id, Reason.STUCK, new Result.BuildPortalDetail(null, t.portalPlaced, "place_failed")));
                }
                return;
            }
            if (t.waitSince == -3) {
                BaritoneBridge.cancelEverything();
                t.waitSince = -1;
            }
            if (t.waitSince == -1) {
                int src = findInventoryIndex(me, item);
                if (src < 0) {
                    BreakGuard.closeAll("portal no item");
                    finish(Result.failed(t.id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of(item, 1))));
                    return;
                }
                equip(mc, me, src);
                t.waitSince = -2;
                t.opStartTick = tick;
                return;
            }
            if (t.waitSince == -2) {
                if (tick - t.opStartTick < PLACE_SETTLE_TICKS) {
                    return;
                }
                BlockPos sp = posOf(pl.support());
                Vec3 hit = new Vec3(sp.getX() + 0.5 + pl.dx() * 0.5, sp.getY() + 0.5 + pl.dy() * 0.5, sp.getZ() + 0.5 + pl.dz() * 0.5);
                Direction face = pl.dy() > 0 ? Direction.UP : pl.dx() > 0 ? Direction.EAST : pl.dx() < 0 ? Direction.WEST
                        : pl.dz() > 0 ? Direction.SOUTH : Direction.NORTH;
                me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit);
                BreakGuard.allowPlace("portal " + item);
                var res = mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, sp, false));
                me.swing(InteractionHand.MAIN_HAND);
                t.waitSince = tick;
                CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: place {} at {} via {} {} -> {}", item, tp, sp, face, res);
                return;
            }
            if (tick - t.waitSince >= PLACE_CONFIRM_TICKS) {
                if (t.portalRetry < 1) {
                    t.portalRetry++;
                    t.waitSince = -1;
                    CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: {} not there yet, retry", tp);
                } else {
                    BreakGuard.closeAll("portal place failed");
                    finish(Result.failed(t.id, Reason.STUCK, new Result.BuildPortalDetail(null, t.portalPlaced, "place_failed")));
                }
            }
            return;
        }
        if (t.opStage == 2) {
            if (t.scaffoldIndex >= t.scaffoldCells.size()) {
                mc.gameMode.stopDestroyBlock();
                t.opStage = 3;
                return;
            }
            BlockPos bp = posOf(t.scaffoldCells.get(t.scaffoldIndex));
            if (mc.level.getBlockState(bp).isAir()) {
                mc.gameMode.stopDestroyBlock();
                t.scaffoldIndex++;
                t.opBreaking = false;
                t.opStartTick = tick;
                return;
            }
            if (!t.opBreaking) {
                int pick = findPickaxe(me);
                if (pick >= 0) {
                    equip(mc, me, pick);
                }
                Block sb = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(t.scaffoldId));
                BreakGuard.allowBreakOnly(sb == null ? List.of() : List.of(sb), "portal scaffold");
                me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(bp));
                mc.gameMode.startDestroyBlock(bp, Direction.UP);
                t.opBreaking = true;
                t.opStartTick = tick;
            }
            mc.gameMode.continueDestroyBlock(bp, Direction.UP);
            me.swing(InteractionHand.MAIN_HAND);
            if (tick - t.opStartTick > SCAFFOLD_BREAK_TIMEOUT_TICKS) {
                mc.gameMode.stopDestroyBlock();
                CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: scaffold at {} won't break, leaving it", bp);
                t.scaffoldIndex++;
                t.opBreaking = false;
            }
            return;
        }
        // stage 3：检查门框
        BreakGuard.closeAll("portal done");
        Block ob = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(t.portalBlock));
        int ok = 0;
        for (PortalLogic.Cell c : t.frameCells) {
            if (ob != null && mc.level.getBlockState(posOf(c)).is(ob)) {
                ok++;
            }
        }
        if (ok < t.frameCells.size()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: frame incomplete {}/{}", ok, t.frameCells.size());
            finish(Result.failed(t.id, Reason.STUCK, new Result.BuildPortalDetail(null, t.portalPlaced, "frame_incomplete")));
            return;
        }
        lastPortal = new PortalMemory(t.portalSite, t.portalLight, t.portalInside, Observer.dim(mc));
        CompanionMod.LOG.info(CompanionMod.TAG + "build_portal: frame complete, inside={} light={}", t.portalInside, t.portalLight);
        finish(Result.ok(t.id, new Result.BuildPortalDetail(t.portalInside == null ? null : protoPos(t.portalInside), t.portalPlaced, null)));
    }

    private void startLightPortal(String id, Minecraft mc) {
        cancelCurrent();
        BaritoneBridge.cancelEverything();
        send(MessageType.ACK, Ack.accepted(id));
        LocalPlayer me = mc.player;
        if (lastPortal == null || lastPortal.light() == null || lastPortal.inside() == null || !java.util.Objects.equals(lastPortal.dim(), Observer.dim(mc))) {
            CompanionMod.LOG.info(CompanionMod.TAG + "light_portal: no portal built here -> item_not_found");
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND));
            return;
        }
        if (findInventoryIndex(me, "minecraft:flint_and_steel") < 0) {
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of("minecraft:flint_and_steel", 1))));
            return;
        }
        Task t = new Task(id, CommandName.LIGHT_PORTAL, null, tick);
        t.portalSite = lastPortal.site();
        t.portalLight = lastPortal.light();
        t.portalInside = lastPortal.inside();
        t.standPos = pickStand(mc, lastPortal.site(), me.blockPosition());
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        t.opStage = 0;
        t.opStartTick = tick;
        current = t;
        IBaritone b = BaritoneBridge.baritone();
        if (b != null) {
            setGoal(b, t, t.standPos, 1);
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "light_portal: light={} inside={}", t.portalLight, t.portalInside);
    }

    private void tickLightPortal(Minecraft mc, LocalPlayer me) {
        Task t = current;
        if (walkingToStand(t, mc, me)) {
            return;
        }
        BlockPos inside = posOf(t.portalInside);
        if (mc.level.getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
            CompanionMod.LOG.info(CompanionMod.TAG + "light_portal: lit");
            finish(Result.ok(t.id, new Result.LightPortalDetail(protoPos(t.portalInside))));
            return;
        }
        if (t.waitSince == -1) {
            int src = findInventoryIndex(me, "minecraft:flint_and_steel");
            if (src < 0) {
                finish(Result.failed(t.id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of("minecraft:flint_and_steel", 1))));
                return;
            }
            equip(mc, me, src);
            t.waitSince = -2;
            t.opStartTick = tick;
            return;
        }
        if (t.waitSince == -2) {
            if (tick - t.opStartTick < PLACE_SETTLE_TICKS) {
                return;
            }
            BlockPos sp = posOf(t.portalLight);
            Vec3 hit = new Vec3(sp.getX() + 0.5, sp.getY() + 1.0, sp.getZ() + 0.5);
            me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit);
            var res = mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, sp, false));
            me.swing(InteractionHand.MAIN_HAND);
            t.waitSince = tick;
            CompanionMod.LOG.info(CompanionMod.TAG + "light_portal: flint on {} -> {}", sp, res);
            return;
        }
        if (tick - t.waitSince >= LIGHT_CONFIRM_TICKS) {
            if (t.portalRetry < 1) {
                t.portalRetry++;
                t.waitSince = -1;
            } else {
                CompanionMod.LOG.info(CompanionMod.TAG + "light_portal: no portal block appeared -> stuck");
                finish(Result.failed(t.id, Reason.STUCK));
            }
        }
    }

    private void startEnterPortal(String id, Minecraft mc) {
        cancelCurrent();
        BaritoneBridge.cancelEverything();
        send(MessageType.ACK, Ack.accepted(id));
        LocalPlayer me = mc.player;
        BlockPos target = null;
        if (lastPortal != null && lastPortal.inside() != null && java.util.Objects.equals(lastPortal.dim(), Observer.dim(mc))
                && mc.level.getBlockState(posOf(lastPortal.inside())).is(Blocks.NETHER_PORTAL)) {
            target = posOf(lastPortal.inside());
        } else {
            BlockPos feet = me.blockPosition();
            double best = Double.MAX_VALUE;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int dx = -8; dx <= 8; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        var pst = mc.level.getBlockState(p.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz));
                        if (pst.is(Blocks.NETHER_PORTAL) || pst.is(Blocks.END_PORTAL)) {
                            double d = dx * dx + dy * dy + dz * dz;
                            if (d < best) {
                                best = d;
                                target = p.immutable();
                            }
                        }
                    }
                }
            }
        }
        if (target == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "enter_portal: no portal within 8 -> item_not_found");
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND));
            return;
        }
        Task t = new Task(id, CommandName.ENTER_PORTAL, null, tick);
        t.portalInside = cellOf(target);
        t.startDim = Observer.dim(mc);
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        current = t;
        IBaritone b = BaritoneBridge.baritone();
        if (b != null) {
            setGoal(b, t, target, 0);
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "enter_portal: walking into {} from {}", target, t.startDim);
    }

    private void tickEnterPortal(Minecraft mc, LocalPlayer me) {
        Task t = current;
        String dim = Observer.dim(mc);
        if (dim != null && !dim.equals(t.startDim)) {
            BaritoneBridge.cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "enter_portal: arrived in {}", dim);
            finish(Result.ok(t.id, new Result.EnterPortalDetail(dim)));
            return;
        }
        if (tick - t.startTick > ENTER_TIMEOUT_TICKS) {
            BaritoneBridge.cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "enter_portal: 60 s and still here -> stuck");
            finish(Result.failed(t.id, Reason.STUCK));
            return;
        }
        BlockPos inside = posOf(t.portalInside);
        var here = mc.level.getBlockState(me.blockPosition());
        if (here.is(Blocks.NETHER_PORTAL) || here.is(Blocks.END_PORTAL)) {
            BaritoneBridge.cancelEverything(); // 站在门里别动（下界门 4 秒传送，末地门一碰就走）
            return;
        }
        var portalBlock = mc.level.getBlockState(inside);
        if (!portalBlock.is(Blocks.NETHER_PORTAL) && !portalBlock.is(Blocks.END_PORTAL)) {
            CompanionMod.LOG.info(CompanionMod.TAG + "enter_portal: portal at {} is gone -> item_not_found", inside);
            finish(Result.failed(t.id, Reason.ITEM_NOT_FOUND));
            return;
        }
        IBaritone b = BaritoneBridge.baritone();
        if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS && !b.getPathingBehavior().isPathing()
                && !b.getCustomGoalProcess().isActive()) {
            setGoal(b, t, inside, 0);
        }
    }

    private void startExplore(String id, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        send(MessageType.ACK, Ack.accepted(id));
        LocalPlayer me = mc.player;
        Task t = new Task(id, CommandName.EXPLORE, null, tick);
        t.exploreStartMs = System.currentTimeMillis();
        current = t;
        b.getExploreProcess().explore(me.getBlockX(), me.getBlockZ());
        CompanionMod.LOG.info(CompanionMod.TAG + "explore started from ({}, {}) dim={}", me.getBlockX(), me.getBlockZ(), Observer.dim(mc));
    }

    private void tickExplore(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        LandmarkLogic.Found f = LandmarkScanner.lastFound;
        if (f != null && LandmarkScanner.lastFoundAtMs > t.exploreStartMs
                && ("fortress".equals(f.kind()) || "stronghold".equals(f.kind()) || "end_portal".equals(f.kind()))) {
            BaritoneBridge.cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "explore: found {} at ({}, {}, {})", f.kind(), Math.round(f.x()), Math.round(f.y()), Math.round(f.z()));
            finish(Result.ok(t.id, new Result.ExploreDetail(f.kind(), new Pos(f.x(), f.y(), f.z()))));
            return;
        }
        if (tick - t.startTick > EXPLORE_TIMEOUT_TICKS) {
            BaritoneBridge.cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "explore: 5 min, nothing -> item_not_found");
            finish(Result.failed(t.id, Reason.ITEM_NOT_FOUND));
            return;
        }
        Player owner = findOwner(mc);
        String dim = Observer.dim(mc);
        if (owner != null) {
            double od = me.distanceTo(owner);
            if (t.exploreWaiting) {
                if (od <= LeadLogic.nearLimit(dim)) {
                    t.exploreWaiting = false;
                    if (b != null) {
                        b.getExploreProcess().explore(me.getBlockX(), me.getBlockZ());
                    }
                    send(MessageType.PROGRESS, new Progress(t.id, "resumed", round(od), Observer.pos(me)));
                } else {
                    me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, owner.getEyePosition());
                    return;
                }
            } else if (od > LeadLogic.farLimit(dim)) {
                t.exploreWaiting = true;
                BaritoneBridge.cancelEverything();
                send(MessageType.PROGRESS, new Progress(t.id, "waiting", round(od), Observer.pos(me)));
                CompanionMod.LOG.info(CompanionMod.TAG + "explore: owner {} behind -> waiting", round(od));
                return;
            }
        }
        if (tick - t.lastProgressTick >= 200) {
            t.lastProgressTick = tick;
            send(MessageType.PROGRESS, new Progress(t.id, "exploring", null, Observer.pos(me)));
        }
        if (b != null && tick - t.lastReplanTick >= EXPLORE_REISSUE_TICKS && !b.getExploreProcess().isActive()) {
            t.lastReplanTick = tick;
            b.getExploreProcess().explore(me.getBlockX(), me.getBlockZ());
        }
    }

    // ---------------- 任务单 21：带路 ----------------

    /** lead：和 goto 一样走到坐标（hub 已把地点名换成坐标），但走在前面：主人落后就停下等，前方有岩浆 / 断崖就停下问。 */
    private void startLead(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        send(MessageType.ACK, Ack.accepted(id));
        if (args == null || !args.has("x") || !args.has("y") || !args.has("z")) {
            CompanionMod.LOG.info(CompanionMod.TAG + "lead: no coordinates -> no_place_known");
            finish(Result.failed(id, Reason.NO_PLACE_KNOWN));
            return;
        }
        Task t = new Task(id, CommandName.LEAD, args, tick);
        t.devGoal = new Pos(args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble());
        t.leadLandmark = args.has("landmark") && !args.get("landmark").isJsonNull() ? args.get("landmark").getAsString() : null;
        current = t;
        setGoal(b, t, goalPos(null, t), 2);
        CompanionMod.LOG.info(CompanionMod.TAG + "lead started target={} place={} landmark={}", t.devGoal,
                args.has("place") ? args.get("place").getAsString() : "-", t.leadLandmark);
    }

    private void tickLead(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        Vec3 target = new Vec3(t.devGoal.x(), t.devGoal.y(), t.devGoal.z());
        double dist = me.position().distanceTo(target);
        if (dist <= ARRIVE_DISTANCE) {
            BaritoneBridge.cancelEverything();
            finish(Result.ok(t.id, new Result.GotoDetail(round(dist))));
            return;
        }
        Player owner = findOwner(mc);
        if (owner == null) {
            if (t.ownerMissingSince < 0) {
                t.ownerMissingSince = tick;
            }
            if (tick - t.ownerMissingSince > LeadLogic.OWNER_LOST_TICKS) {
                BaritoneBridge.cancelEverything();
                CompanionMod.LOG.info(CompanionMod.TAG + "lead: owner gone for 30 s -> target_lost");
                finish(Result.failed(t.id, Reason.TARGET_LOST, new Result.GotoDetail(round(dist))));
                return;
            }
            if (!t.leadWaiting) {
                t.leadWaiting = true;
                BaritoneBridge.cancelEverything();
                send(MessageType.PROGRESS, new Progress(t.id, "waiting", round(dist), Observer.pos(me)));
                CompanionMod.LOG.info(CompanionMod.TAG + "lead: can't see owner, waiting");
            }
            return;
        }
        t.ownerMissingSince = -1;
        // 危险停着：等主人说"继续"（continue 命令），2 分钟没等到按 stuck 结束
        if (t.leadDanger != null) {
            me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, owner.getEyePosition());
            if (tick - t.leadPauseStart > LeadLogic.DANGER_WAIT_TICKS) {
                CompanionMod.LOG.info(CompanionMod.TAG + "lead: no 'continue' in 2 min -> stuck");
                finish(Result.failed(t.id, Reason.STUCK, new Result.GotoDetail(round(dist))));
            }
            return;
        }
        String dim = Observer.dim(mc);
        double od = me.distanceTo(owner);
        if (t.leadWaiting) {
            if (od <= LeadLogic.nearLimit(dim)) {
                t.leadWaiting = false;
                setGoal(b, t, goalPos(null, t), 2);
                send(MessageType.PROGRESS, new Progress(t.id, "resumed", round(dist), Observer.pos(me)));
                CompanionMod.LOG.info(CompanionMod.TAG + "lead: owner back within {} -> go on", LeadLogic.nearLimit(dim));
            } else {
                me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, owner.getEyePosition());
            }
            return;
        }
        if (od > LeadLogic.farLimit(dim)) {
            t.leadWaiting = true;
            BaritoneBridge.cancelEverything();
            send(MessageType.PROGRESS, new Progress(t.id, "waiting", round(dist), Observer.pos(me)));
            CompanionMod.LOG.info(CompanionMod.TAG + "lead: owner {} blocks behind (> {}) -> waiting", round(od), LeadLogic.farLimit(dim));
            return;
        }
        if (t.pathFailed) {
            if (digOutRetry(t, b, goalPos(null, t), 2)) {
                return;
            }
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.PATH_FAILED, new Result.GotoDetail(round(dist))));
            return;
        }
        if (tick - t.lastDangerCheckTick >= LeadLogic.DANGER_CHECK_EVERY_TICKS) {
            t.lastDangerCheckTick = tick;
            Vec3 mv = me.getDeltaMovement();
            double dx = mv.x;
            double dz = mv.z;
            if (Math.hypot(dx, dz) < 0.02) {
                Vec3 look = me.getLookAngle();
                dx = look.x;
                dz = look.z;
            }
            String danger = LeadLogic.dangerAhead(blockProbe(mc), me.getBlockX(), me.getBlockY(), me.getBlockZ(), dx, dz);
            if (danger != null) {
                BaritoneBridge.cancelEverything();
                t.leadDanger = danger;
                t.leadPauseStart = tick;
                send(MessageType.EVENT, Event.leadPaused(danger, Observer.pos(me), dim));
                send(MessageType.PROGRESS, new Progress(t.id, "danger", round(dist), Observer.pos(me)));
                CompanionMod.LOG.info(CompanionMod.TAG + "lead: {} ahead -> paused, waiting for 'continue'", danger);
                return;
            }
        }
        if (tick - t.lastProgressTick >= MINE_PROGRESS_TICKS) {
            t.lastProgressTick = tick;
            send(MessageType.PROGRESS, new Progress(t.id, "distance", round(dist), Observer.pos(me)));
        }
        if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS) {
            boolean idle = !b.getPathingBehavior().isPathing() && !b.getCustomGoalProcess().isActive();
            if (idle) {
                setGoal(b, t, goalPos(null, t), 2);
            }
        }
    }

    private static LeadLogic.Probe blockProbe(Minecraft mc) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        return new LeadLogic.Probe() {
            @Override
            public boolean isLava(int x, int y, int z) {
                return mc.level.getBlockState(p.set(x, y, z)).is(net.minecraft.world.level.block.Blocks.LAVA);
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                var st = mc.level.getBlockState(p.set(x, y, z));
                return !st.isAir() && !st.getCollisionShape(mc.level, p).isEmpty();
            }
        };
    }

    private void startMine(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        send(MessageType.ACK, Ack.accepted(id));
        Command.MineArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.MineArgs.class);
        String blockId = a == null || a.block() == null ? "" : Rules.normalize(a.block());
        int wanted = a == null ? 1 : Math.max(1, a.count());
        int radius = Math.min(MINE_MAX_RADIUS, Math.max(4, a == null ? 32 : a.radiusOrDefault()));
        if (!rules.canMine(blockId)) {
            CompanionMod.LOG.info(CompanionMod.TAG + "mine: {} not in whitelist (received={}) -> not_allowed", blockId, rules.received());
            finish(Result.failed(id, Reason.NOT_ALLOWED, new Result.MineDetail(0)));
            return;
        }
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        net.minecraft.world.level.block.Block block = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            CompanionMod.LOG.info(CompanionMod.TAG + "mine: unknown block {} -> not_allowed", blockId);
            finish(Result.failed(id, Reason.NOT_ALLOWED, new Result.MineDetail(0)));
            return;
        }
        LocalPlayer me = mc.player;
        if (rules.needsPickaxe(blockId)) {
            int pick = findPickaxe(me);
            if (pick < 0) {
                CompanionMod.LOG.info(CompanionMod.TAG + "mine: {} needs a pickaxe and there is none -> no_materials", blockId);
                finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of("minecraft:wooden_pickaxe", 1))));
                return;
            }
            equip(mc, me, pick);
        }
        int nearby = countBlocksAround(mc, me.blockPosition(), block, radius);
        if (nearby == 0) {
            CompanionMod.LOG.info(CompanionMod.TAG + "mine: no {} within {} blocks -> item_not_found", blockId, radius);
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND, new Result.MineDetail(0)));
            return;
        }
        String drop = rules.dropOf(blockId);
        Task t = new Task(id, CommandName.MINE, args, tick);
        t.blockId = blockId;
        t.block = block;
        t.item = drop;
        t.mine = new GatherLogic.MineProgress(countItem(me, drop), wanted, MINE_STALL_TICKS, tick);
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE); // 只用来判断"这 5 秒动没动"，卡不卡由 MineProgress 定
        t.lastProgressTick = tick;
        current = t;
        // 放行整张可挖清单而不只是目标：要能挖穿泥土 / 石头才够得着矿；清单外的（箱子、墙砖……）仍然碰不得
        List<net.minecraft.world.level.block.Block> allowed = new ArrayList<>();
        for (String idStr : rules.mineableIds()) {
            ResourceLocation r = ResourceLocation.tryParse(idStr);
            net.minecraft.world.level.block.Block bl = r == null ? null : ForgeRegistries.BLOCKS.getValue(r);
            if (bl != null && bl != net.minecraft.world.level.block.Blocks.AIR) {
                allowed.add(bl);
            }
        }
        BreakGuard.allowBreakOnly(allowed, "mine " + blockId);
        b.getMineProcess().mine(block);
        CompanionMod.LOG.info(CompanionMod.TAG + "mine started block={} drop={} wanted={} nearby={} radius={}", blockId, drop, wanted,
                nearby, radius);
    }

    private void tickMine(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        int now = countItem(me, t.item);
        // 走向目标、挖穿泥土时背包不会多东西，但人在动：动了就不算卡（真游戏预检里 20 秒刚好够不着石头）
        boolean moving = !t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick) && t.stuck.movedRecently(tick);
        GatherLogic.MineProgress.Verdict v = t.mine.feed(now, moving, tick);
        if (v == GatherLogic.MineProgress.Verdict.DONE) {
            BaritoneBridge.cancelEverything();
            finish(Result.ok(t.id, new Result.MineDetail(t.mine.mined())));
            return;
        }
        if (v == GatherLogic.MineProgress.Verdict.STALLED) {
            BaritoneBridge.cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "mine: no progress for {} ticks -> stuck (mined {})", MINE_STALL_TICKS, t.mine.mined());
            finish(Result.failed(t.id, Reason.STUCK, new Result.MineDetail(t.mine.mined())));
            return;
        }
        Player owner = findOwner(mc);
        boolean ownerOnline = mc.getConnection() != null && mc.getConnection().getOnlinePlayers().stream()
                .anyMatch(i -> ClientConfig.OWNER_NAME.get().equals(i.getProfile().getName()));
        if (GatherLogic.tooFarFromOwner(ownerOnline, owner != null, owner == null ? 0 : me.distanceTo(owner))) {
            BaritoneBridge.cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "mine: too far from owner (visible={}) -> target_too_far (mined {})", owner != null, t.mine.mined());
            finish(Result.failed(t.id, Reason.TARGET_TOO_FAR, new Result.MineDetail(t.mine.mined())));
            return;
        }
        if (b != null && tick - t.startTick > MINE_GRACE_TICKS && !b.getMineProcess().isActive()
                && !b.getPathingBehavior().isPathing()) {
            // Baritone 自己收手了（附近这种方块挖光了 / 够不着）
            CompanionMod.LOG.info(CompanionMod.TAG + "mine: baritone idle -> item_not_found (mined {})", t.mine.mined());
            finish(Result.failed(t.id, Reason.ITEM_NOT_FOUND, new Result.MineDetail(t.mine.mined())));
            return;
        }
        if (tick - t.lastProgressTick >= MINE_PROGRESS_TICKS) {
            t.lastProgressTick = tick;
            send(MessageType.PROGRESS, new Progress(t.id, "mined", (double) t.mine.mined(), Observer.pos(me)));
        }
    }

    private void startCollect(String id, JsonObject args, Minecraft mc) {
        cancelCurrent();
        IBaritone b = BaritoneBridge.baritone();
        if (b == null) {
            send(MessageType.ACK, Ack.rejected(id));
            return;
        }
        send(MessageType.ACK, Ack.accepted(id));
        Command.CollectArgs a = args == null ? null : ProtocolCodec.gson().fromJson(args, Command.CollectArgs.class);
        String only = a == null ? null : normalizeItem(a.item());
        int radius = Math.max(2, a == null ? 8 : a.radiusOrDefault());
        LocalPlayer me = mc.player;
        if (findAnyItems(mc, me, only, radius).isEmpty()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "collect: nothing within {} blocks (item={}) -> item_not_found", radius, only);
            finish(Result.failed(id, Reason.ITEM_NOT_FOUND, new Result.CollectDetail(0)));
            return;
        }
        if (me.getInventory().getFreeSlot() == -1) {
            finish(Result.failed(id, Reason.INVENTORY_FULL, new Result.CollectDetail(0)));
            return;
        }
        Task t = new Task(id, CommandName.COLLECT, args, tick);
        t.item = only;
        t.radius = radius;
        t.collectStart = totalItems(me);
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        current = t;
        final String want = only;
        b.getFollowProcess().pickup(stack -> want == null || want.equals(idOf(stack)));
        CompanionMod.LOG.info(CompanionMod.TAG + "collect started item={} radius={}", only == null ? "any" : only, radius);
    }

    private void tickCollect(Minecraft mc, LocalPlayer me) {
        Task t = current;
        int picked = Math.max(0, totalItems(me) - t.collectStart);
        boolean leftNearby = !findAnyItems(mc, me, t.item, t.radius * 2).isEmpty();
        if (!leftNearby) {
            if (t.noneLeftSince < 0) {
                t.noneLeftSince = tick;
            } else if (tick - t.noneLeftSince >= COLLECT_NONE_LEFT_TICKS) {
                BaritoneBridge.cancelEverything();
                finish(Result.ok(t.id, new Result.CollectDetail(picked)));
                return;
            }
        } else {
            t.noneLeftSince = -1;
        }
        if (leftNearby && me.getInventory().getFreeSlot() == -1 && tick - t.startTick > 20) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.INVENTORY_FULL, new Result.CollectDetail(picked)));
            return;
        }
        if (t.stuck.feed(me.getX(), me.getY(), me.getZ(), tick)) {
            BaritoneBridge.cancelEverything();
            finish(Result.failed(t.id, Reason.STUCK, new Result.CollectDetail(picked)));
            return;
        }
        if (tick - t.lastProgressTick >= MINE_PROGRESS_TICKS) {
            t.lastProgressTick = tick;
            send(MessageType.PROGRESS, new Progress(t.id, "picked", (double) picked, Observer.pos(me)));
        }
    }

    /** 半径立方体里数这种方块有几个（只看已加载区块；radius ≤ 48 时最多 90 万格，开挖时算一次）。 */
    static int countBlocksAround(Minecraft mc, BlockPos center, net.minecraft.world.level.block.Block block, int radius) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int minY = Math.max(mc.level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(mc.level.getMaxBuildHeight() - 1, center.getY() + radius);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!mc.level.hasChunkAt(p.set(x, 64, z))) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    if (mc.level.getBlockState(p.set(x, y, z)).is(block)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    static List<ItemEntity> findAnyItems(Minecraft mc, LocalPlayer me, String item, int radius) {
        return mc.level.getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(radius),
                e -> item == null || item.equals(idOf(e.getItem())));
    }

    static int totalItems(LocalPlayer me) {
        int n = 0;
        for (ItemStack s : me.getInventory().items) {
            n += s.getCount();
        }
        for (ItemStack s : me.getInventory().offhand) {
            n += s.getCount();
        }
        return n;
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
            if (digOutRetry(t, b, goalPos(owner, t), 2)) {
                return;
            }
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
        if (current != null && current.internal) {
            CompanionMod.LOG.info(CompanionMod.TAG + "internal task {} ended {} {}", current.id, r.status(), r.reason() == null ? "" : r.reason().wire());
            current = null;
            BreakGuard.closeAll("internal end");
            return;
        }
        CompanionMod.LOG.info(CompanionMod.TAG + "result {} {} {}", r.commandId(), r.status(), r.reason() == null ? "" : r.reason().wire());
        send(MessageType.RESULT, r);
        if (current != null) {
            closeMenuIfOpen(current);
        }
        current = null;
        BreakGuard.closeAll("result " + r.status());
    }

    /** 合成 / 烧东西结束（不管成没成）：发一次"关容器"，服务器会把格子里剩的材料退回背包、关掉界面。 */
    private static void closeMenuIfOpen(Task t) {
        if (t.name != CommandName.CRAFT && t.name != CommandName.USE_FURNACE) {
            return;
        }
        LocalPlayer me = Minecraft.getInstance().player;
        if (me != null) {
            me.closeContainer();
        }
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
