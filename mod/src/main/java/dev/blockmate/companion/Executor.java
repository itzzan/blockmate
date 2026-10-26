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
 * executor（手册 6.4）：follow / come_here / wait_here / stop / pick_up / deliver / rejoin；任务单 13 加 goto / mine / collect；任务单 14 加 craft（2×2）/ place / pickup_block。
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
    private static final int BREAK_TIMEOUT_TICKS = 200;      // 10 秒还没挖掉
    private static final int PICK_TIMEOUT_TICKS = 100;       // 挖掉后 5 秒内要捡到
    private static final int PLACE_HOTBAR_SLOT = 8;          // 放方块用快捷栏最后一格

    private enum Phase { APPROACH, THROW, BACK_OFF, WAIT_CONFIRM, BREAK, PICK }

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
        int collectStart;
        // craft / place / pickup_block（任务单 14）
        ArrayDeque<Click> clicks;
        List<CraftPlanner.Cell> cells;
        int batchesLeft;
        int perCraft;
        int batchStartCount;
        int waitSince = -1;
        BlockPos placePos;
        int placeStage;       // place：0 换快捷栏 1 等稳 2 已放等确认
        boolean breaking;
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

    /** hello 下发的白名单（任务单 12）；没收到之前全不许。 */
    private volatile Rules rules = Rules.EMPTY;

    /** 自己放过的方块（任务单 14）：方块 id → 位置，pickup_block 只认这里的。 */
    private final Map<String, BlockPos> placedBlocks = new HashMap<>();

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
            case GOTO -> startGoto(env.id(), cmd.args(), mc);
            case MINE -> startMine(env.id(), cmd.args(), mc);
            case COLLECT -> startCollect(env.id(), cmd.args(), mc);
            case CRAFT -> startCraft(env.id(), cmd.args(), mc);
            case PLACE -> startPlace(env.id(), cmd.args(), mc);
            case PICKUP_BLOCK -> startPickupBlock(env.id(), cmd.args(), mc);
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
        send(MessageType.RESULT, Result.cancelled(current.id));
        CompanionMod.LOG.info(CompanionMod.TAG + "task {} cancelled (replaced)", current.name.wire());
        current = null;
        BaritoneBridge.cancelEverything();
        BreakGuard.closeAll("task replaced");
    }

    /** 与后台断开：立刻停，丢掉任务，不补发 result（细则第 15 节）。 */
    public void onLinkLost() {
        if (current != null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "link lost, dropping task {}", current.name.wire());
            current = null;
        }
        BaritoneBridge.cancelEverything();
        BreakGuard.closeAll("link lost");
    }

    /** 离开世界。 */
    public void reset() {
        current = null;
        BreakGuard.closeAll("left world");
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
            case GOTO -> tickGoto(mc, me);
            case MINE -> tickMine(mc, me);
            case COLLECT -> tickCollect(mc, me);
            case CRAFT -> tickCraft(mc, me);
            case PLACE -> tickPlace(mc, me);
            case PICKUP_BLOCK -> tickPickupBlock(mc, me);
            default -> {
            }
        }
    }

    // ---------------- 任务单 14：craft（2×2）/ place / pickup_block ----------------

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
            me.closeContainer(); // 把别的界面关掉，用背包自带的 2×2
        }
        if (!me.inventoryMenu.getCarried().isEmpty()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: cursor not empty -> stuck");
            finish(Result.failed(id, Reason.STUCK, new Result.CraftDetail(0)));
            return;
        }
        // 找配方：成品是它、2×2 能做的；都要工作台的留给第二批
        List<CraftingRecipe> small = new ArrayList<>();
        boolean anyRecipe = false;
        for (CraftingRecipe r : mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack out = r.getResultItem(mc.level.registryAccess());
            if (out == null || out.isEmpty() || !item.equals(idOf(out))) {
                continue;
            }
            anyRecipe = true;
            if (r.canCraftInDimensions(2, 2)) {
                small.add(r);
            }
        }
        if (!anyRecipe) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: no recipe for {}", item);
            finish(Result.failed(id, Reason.NO_RECIPE, new Result.CraftDetail(0)));
            return;
        }
        if (small.isEmpty()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: {} needs a crafting table (3x3) -> no_materials crafting_table", item);
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of("minecraft:crafting_table", 1))));
            return;
        }
        Map<String, Integer> inv = inventorySummary(me);
        CraftingRecipe chosen = null;
        List<CraftPlanner.Cell> chosenCells = null;
        Map<String, Integer> firstMissing = null;
        for (CraftingRecipe r : small) {
            List<CraftPlanner.Cell> cells = cellsFor(r, 2, 2);
            if (cells == null) {
                continue;
            }
            CraftPlanner.Plan plan = CraftPlanner.plan(cells, inv);
            if (plan.ok()) {
                chosen = r;
                chosenCells = cells;
                break;
            }
            if (firstMissing == null) {
                firstMissing = plan.missing();
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
        t.clicks = buildClicks(me, CraftPlanner.plan(chosenCells, inv));
        current = t;
        CompanionMod.LOG.info(CompanionMod.TAG + "craft started item={} wanted={} recipe={} per_craft={} batches={} clicks={}", item, wanted,
                chosen.getId(), t.perCraft, t.batchesLeft, t.clicks.size());
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
     * 把分配好的材料一格一格放进 2×2（背包菜单的格子 1–4），最后 shift 点结果格（0）。
     * 每个格子三下：拿起材料叠 → 右键放一个 → 把剩下的放回去。
     */
    private static ArrayDeque<Click> buildClicks(LocalPlayer me, CraftPlanner.Plan plan) {
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
            int srcSlot = ThrowPlan.slotFor(src);
            q.add(new Click(srcSlot, 0, ClickType.PICKUP));
            q.add(new Click(1 + g, 1, ClickType.PICKUP));
            q.add(new Click(srcSlot, 0, ClickType.PICKUP));
        }
        q.add(new Click(0, 0, ClickType.QUICK_MOVE));
        return q;
    }

    private void tickCraft(Minecraft mc, LocalPlayer me) {
        Task t = current;
        if (!t.clicks.isEmpty()) {
            Click c = t.clicks.poll();
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, c.slot(), c.button(), c.type(), me);
            if (t.clicks.isEmpty()) {
                t.waitSince = tick;
            }
            return;
        }
        int now = countItem(me, t.item);
        if (now >= t.batchStartCount + t.perCraft) {
            t.batchesLeft--;
            send(MessageType.PROGRESS, new Progress(t.id, "crafted", (double) (now - t.startCount), Observer.pos(me)));
            if (t.batchesLeft <= 0) {
                finish(Result.ok(t.id, new Result.CraftDetail(now - t.startCount)));
                return;
            }
            CraftPlanner.Plan plan = CraftPlanner.plan(t.cells, inventorySummary(me));
            if (!plan.ok()) {
                CompanionMod.LOG.info(CompanionMod.TAG + "craft: ran out after {} ({})", now - t.startCount, plan.missing());
                finish(Result.failed(t.id, Reason.NO_MATERIALS, new Result.CraftDetail(now - t.startCount, plan.missing())));
                return;
            }
            t.batchStartCount = now;
            t.clicks = buildClicks(me, plan);
            t.waitSince = -1;
            return;
        }
        if (t.waitSince >= 0 && tick - t.waitSince >= CRAFT_RESULT_WAIT_TICKS) {
            CompanionMod.LOG.info(CompanionMod.TAG + "craft: result never arrived (have {} want {}) -> stuck", now, t.batchStartCount + t.perCraft);
            finish(Result.failed(t.id, Reason.STUCK, new Result.CraftDetail(now - t.startCount)));
        }
    }

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
        LocalPlayer me = mc.player;
        int src = findInventoryIndex(me, blockId);
        if (src < 0) {
            finish(Result.failed(id, Reason.NO_MATERIALS, new Result.CraftDetail(0, Map.of(blockId, 1))));
            return;
        }
        BlockPos feet = me.blockPosition();
        PlaceSpot.Cell spot = PlaceSpot.pick(new PlaceSpot.Cell(feet.getX(), feet.getY(), feet.getZ()),
                c -> {
                    var st = mc.level.getBlockState(new BlockPos(c.x(), c.y(), c.z()));
                    return st.isAir() || st.canBeReplaced();
                },
                c -> {
                    BlockPos p = new BlockPos(c.x(), c.y(), c.z());
                    return mc.level.getBlockState(p).isFaceSturdy(mc.level, p, Direction.UP);
                }).orElse(null);
        if (spot == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "place: no free spot next to feet -> stuck");
            finish(Result.failed(id, Reason.STUCK));
            return;
        }
        Task t = new Task(id, CommandName.PLACE, args, tick);
        t.blockId = blockId;
        t.placePos = new BlockPos(spot.x(), spot.y(), spot.z());
        t.placeStage = 0;
        if (src < 9) {
            me.getInventory().selected = src;        // 已经在快捷栏，直接选中
            t.placeStage = 1;
        } else {
            mc.gameMode.handleInventoryMouseClick(me.inventoryMenu.containerId, ThrowPlan.slotFor(src), PLACE_HOTBAR_SLOT, ClickType.SWAP, me);
            me.getInventory().selected = PLACE_HOTBAR_SLOT;
            t.placeStage = 1;
        }
        t.phaseStartTick = tick;
        current = t;
        CompanionMod.LOG.info(CompanionMod.TAG + "place started block={} at={} from_slot={}", blockId, t.placePos, src);
    }

    private void tickPlace(Minecraft mc, LocalPlayer me) {
        Task t = current;
        if (t.placeStage == 1) {
            if (tick - t.phaseStartTick < PLACE_SETTLE_TICKS) {
                return;
            }
            ItemStack held = me.getMainHandItem();
            if (!t.blockId.equals(idOf(held))) {
                CompanionMod.LOG.info(CompanionMod.TAG + "place: hand holds {} not {} -> stuck", idOf(held), t.blockId);
                finish(Result.failed(t.id, Reason.STUCK));
                return;
            }
            BlockPos below = t.placePos.below();
            Vec3 hit = new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
            BreakGuard.allowPlace("place " + t.blockId);
            var res = mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
            me.swing(InteractionHand.MAIN_HAND);
            CompanionMod.LOG.info(CompanionMod.TAG + "place: useItemOn -> {}", res);
            t.placeStage = 2;
            t.phaseStartTick = tick;
            return;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(t.blockId));
        if (block != null && mc.level.getBlockState(t.placePos).is(block)) {
            placedBlocks.put(t.blockId, t.placePos);
            finish(Result.ok(t.id, new Result.PlaceDetail(new Pos(t.placePos.getX(), t.placePos.getY(), t.placePos.getZ()))));
            return;
        }
        if (tick - t.phaseStartTick >= PLACE_CONFIRM_TICKS) {
            CompanionMod.LOG.info(CompanionMod.TAG + "place: block did not appear at {} -> stuck", t.placePos);
            finish(Result.failed(t.id, Reason.STUCK));
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
        LocalPlayer me = mc.player;
        Task t = new Task(id, CommandName.PICKUP_BLOCK, args, tick);
        t.blockId = blockId;
        t.block = block;
        t.placePos = pos;
        t.item = blockId; // 工作台、火把、床掉落的物品和方块同名
        t.startCount = countItem(me, blockId);
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS, STUCK_MIN_MOVE);
        t.phaseStartTick = tick;
        double dist = me.position().distanceTo(Vec3.atCenterOf(pos));
        IBaritone b = BaritoneBridge.baritone();
        if (dist > 4.0 && b != null) {
            t.phase = Phase.APPROACH;
            setGoal(b, t, pos, 2);
        } else {
            t.phase = Phase.BREAK;
        }
        current = t;
        CompanionMod.LOG.info(CompanionMod.TAG + "pickup_block started block={} at={} dist={}", blockId, pos, round(dist));
    }

    private void tickPickupBlock(Minecraft mc, LocalPlayer me) {
        Task t = current;
        IBaritone b = BaritoneBridge.baritone();
        switch (t.phase) {
            case APPROACH -> {
                double dist = me.position().distanceTo(Vec3.atCenterOf(t.placePos));
                if (dist <= 3.5) {
                    BaritoneBridge.cancelEverything();
                    t.phase = Phase.BREAK;
                    t.phaseStartTick = tick;
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
                if (b != null && tick - t.lastReplanTick >= REPLAN_EVERY_TICKS
                        && !b.getPathingBehavior().isPathing() && !b.getCustomGoalProcess().isActive()) {
                    setGoal(b, t, t.placePos, 2);
                }
            }
            case BREAK -> {
                if (mc.level.getBlockState(t.placePos).isAir()) {
                    mc.gameMode.stopDestroyBlock();
                    t.breaking = false;
                    t.phase = Phase.PICK;
                    t.phaseStartTick = tick;
                    if (b != null) {
                        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(t.placePos, 0)); // 走过去把掉落捡起来
                    }
                    return;
                }
                if (!t.breaking) {
                    BreakGuard.allowBreakOnly(List.of(t.block), "pickup_block " + t.blockId);
                    mc.gameMode.startDestroyBlock(t.placePos, Direction.UP);
                    t.breaking = true;
                }
                mc.gameMode.continueDestroyBlock(t.placePos, Direction.UP);
                me.swing(InteractionHand.MAIN_HAND);
                if (tick - t.phaseStartTick >= BREAK_TIMEOUT_TICKS) {
                    mc.gameMode.stopDestroyBlock();
                    CompanionMod.LOG.info(CompanionMod.TAG + "pickup_block: still not broken after {} ticks -> stuck", BREAK_TIMEOUT_TICKS);
                    finish(Result.failed(t.id, Reason.STUCK));
                }
            }
            case PICK -> {
                if (countItem(me, t.item) > t.startCount) {
                    BaritoneBridge.cancelEverything();
                    placedBlocks.remove(t.blockId);
                    finish(Result.ok(t.id, new Result.PlaceDetail(new Pos(t.placePos.getX(), t.placePos.getY(), t.placePos.getZ()))));
                    return;
                }
                if (tick - t.phaseStartTick >= PICK_TIMEOUT_TICKS) {
                    BaritoneBridge.cancelEverything();
                    placedBlocks.remove(t.blockId);
                    finish(Result.failed(t.id, Reason.NOT_PICKED));
                }
            }
            default -> {
            }
        }
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
        t.stuck = new StuckDetector(STUCK_WINDOW_TICKS * 4, STUCK_MIN_MOVE); // 挖的时候本来就站着不动，放宽到 20 秒
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
        GatherLogic.MineProgress.Verdict v = t.mine.feed(now, tick);
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
        if (current != null && current.name == CommandName.CRAFT) {
            closeInventoryGrid();
        }
        current = null;
        BreakGuard.closeAll("result " + r.status());
    }

    /** 合成结束（不管成没成）：发一次"关背包"，服务器会把 2×2 里剩的材料退回背包。 */
    private static void closeInventoryGrid() {
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
