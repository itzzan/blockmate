package dev.blockmate.server.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blockmate.protocol.Ack;
import dev.blockmate.protocol.BlockRules;
import dev.blockmate.protocol.Pos;
import dev.blockmate.protocol.Reason;
import dev.blockmate.protocol.Chat;
import dev.blockmate.protocol.Command;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Event;
import dev.blockmate.protocol.Hello;
import dev.blockmate.protocol.HelloOk;
import dev.blockmate.protocol.KeeperCmd;
import dev.blockmate.protocol.CombatControl;
import dev.blockmate.protocol.IdleControl;
import dev.blockmate.protocol.MobRules;
import dev.blockmate.protocol.RulesUpdate;
import dev.blockmate.protocol.KeeperControl;
import dev.blockmate.protocol.KeeperStatus;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Notice;
import dev.blockmate.protocol.Peer;
import dev.blockmate.protocol.PreferenceAction;
import dev.blockmate.protocol.Preferences;
import dev.blockmate.protocol.Progress;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.Reply;
import dev.blockmate.protocol.Result;
import dev.blockmate.protocol.Snapshot;
import dev.blockmate.protocol.Status;
import dev.blockmate.protocol.TaskInfo;
import dev.blockmate.server.Config;
import dev.blockmate.server.brain.Blueprint;
import dev.blockmate.server.brain.EyeLocator;
import dev.blockmate.server.brain.Landmarks;
import dev.blockmate.server.brain.MobNames;
import dev.blockmate.server.brain.Progression;
import dev.blockmate.server.memory.StoredWorldFact;
import dev.blockmate.server.brain.Brain;
import dev.blockmate.server.brain.BrainContext;
import dev.blockmate.server.brain.ItemNames;
import dev.blockmate.server.brain.Knowledge;
import dev.blockmate.server.brain.Shortcuts;
import dev.blockmate.server.brain.SnapshotSummary;
import dev.blockmate.server.brain.Tools;
import dev.blockmate.server.gateway.ModelException;
import dev.blockmate.server.gateway.ModelReply;
import dev.blockmate.server.gateway.ToolCall;
import dev.blockmate.server.memory.Episode;
import dev.blockmate.server.memory.Memory;
import dev.blockmate.server.memory.Preference;
import dev.blockmate.server.memory.StoredEvent;
import dev.blockmate.server.memory.StoredMessage;
import dev.blockmate.server.memory.StoredPlace;
import dev.blockmate.server.memory.StoredPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Hub（手册 5.5 第 2 条）：管连接、管任务槽、把网页的话交给 Brain、把工具调用变成 command、把结果变成人话。
 * 所有方法都在 hub 线程上跑（WsEndpoints 通过 {@link #submit} 投递）；模型调用放到 brainExec 上，回来再投递回 hub 线程。
 */
public final class Hub {

    /** 一条连接的出口。 */
    public interface Sender {
        void send(Envelope envelope);

        void close(int code, String reason);
    }

    private static final Logger log = LoggerFactory.getLogger(Hub.class);
    private static final long STATUS_INTERVAL_MS = 5000;
    private static final String NOTICE_OFFLINE = "我现在不在游戏里";
    private static final String NOTICE_MODEL_DOWN = "我脑子有点转不动，等一下再和我说";

    private final Config cfg;
    private final Memory memory;
    private final Brain brain;
    private final ItemNames names;
    private final ScheduledExecutorService exec;
    private final ExecutorService brainExec;
    private final TaskSlot slot;

    private Sender mc;
    private Sender keeper;
    private final List<Sender> uis = new ArrayList<>();
    private Snapshot lastSnapshot;
    private boolean mcOnline;
    private KeeperStatus keeperStatus;
    private String keeperDesired;
    /** 自动上下线（手册 7.2 可选项）：开关存在设置表 keeper_auto；没配 rcon 时 autoAvailable=false。 */
    private final boolean autoAvailable;
    private volatile boolean autoPresence;
    private Boolean ownerOnline;
    private final PresencePolicy presence;
    private int rconFailures;
    /** 给模型看的历史：分块滑动窗口（history_n 到 2×history_n 条），让提示前缀稳定、Ollama 缓存命中。 */
    private final HistoryWindow historyWindow;
    /** 当前计划（任务单 10）：单个动作就是只有一步的计划；null = 没有。 */
    private PlanRun plan;
    /** 计划当前步对应的 command 信封 id。 */
    private String planCommandId;
    private boolean modelOk = true;
    private long lastStatusPush;
    /** stop 命令的 id → 来源（button / model）。 */
    private final Map<String, String> stopCommands = new HashMap<>();
    /** command id → 触发它的那条 owner 消息 id（回复时作为 in_reply_to）。 */
    private final Map<String, String> commandSource = new HashMap<>();

    private final Knowledge knowledge;

    /** 任务单 17：progress 表里记过的阶段（启动后第一次用时读一次，之后只增）。 */
    private java.util.Set<String> progressDone;
    /** 上一次算过进度时的背包；背包没变就不重算。 */
    private Map<String, Integer> lastInventoryForProgress;
    /** 同种世界事实在这个距离内已有记录就不重复记。 */
    static final double FACT_DEDUPE_RADIUS = 32;

    // ---------- 任务单 18：空闲循环 ----------
    private final IdlePolicy idle;
    /** 网页开关（设置表 idle_enabled），没设过时取 config 的 idle.enabled。 */
    private boolean idleEnabled;
    /** 正在模型里跑的请求数（决策、说一句、四选一、想一想）；> 0 就是"模型没空"。 */
    private int brainBusy;
    private boolean idleThinking;
    /** 当前计划是谁发起的：owner（主人要的）/ idle（它提议、主人点头的），plans 表的 source 列。 */
    private String planSource = "owner";
    private String pendingPlanSource = "owner";

    // ---------- 任务单 23：末地 ----------
    /** 主人投过的末影之眼（主世界），两次隔 100 格以上就能算要塞。 */
    private final List<EyeLocator.Throw> eyeThrows = new ArrayList<>();
    /** 上次死在末地：重生后按记过的末地传送门走回去。 */
    private boolean diedInEnd;

    // ---------- 任务单 22：计划里的"问主人"步 ----------
    /** 正在等主人回答的那一步（ask_owner）；主人说"点 / 好"继续，"不"整个作废，2 分钟不答也作废。 */
    private PlanRun.Step awaitingStep;
    private long awaitingSince;
    static final long ASK_OWNER_TIMEOUT_MS = 120_000;

    // ---------- 任务单 21：带路 ----------
    /** 带路命令 id → 目的地的叫法（家 / 村庄 / 那个坐标），到了说一句用。 */
    private final Map<String, String> leadDest = new HashMap<>();
    /** 带路因危险停着的那条命令 id；主人说"继续"才下 continue。 */
    private String leadPausedCommandId;

    // ---------- 任务单 19：战斗开关 ----------
    /** off / defend / protect；设置表 combat_mode 盖过 config 的 combat.mode。 */
    private String combatMode;

    public Hub(Config cfg, Memory memory, Brain brain, ItemNames names, ScheduledExecutorService exec,
               ExecutorService brainExec) {
        this(cfg, memory, brain, Knowledge.empty(), names, exec, brainExec);
    }

    public Hub(Config cfg, Memory memory, Brain brain, Knowledge knowledge, ItemNames names, ScheduledExecutorService exec,
               ExecutorService brainExec) {
        this.cfg = cfg;
        this.memory = memory;
        this.brain = brain;
        this.knowledge = knowledge == null ? Knowledge.empty() : knowledge;
        this.names = names;
        this.exec = exec;
        this.brainExec = brainExec;
        this.slot = new TaskSlot(cfg.timeouts);
        this.keeperDesired = memory.setting("keeper_desired").orElse("stopped");
        this.autoAvailable = cfg.rcon != null;
        this.autoPresence = autoAvailable && "on".equals(memory.setting("keeper_auto").orElse("off"));
        this.presence = new PresencePolicy(cfg.autoPresence.offlineAfterMin() * 60_000L);
        this.historyWindow = new HistoryWindow(cfg.historyN, cfg.historyN * 2);
        this.idle = new IdlePolicy(cfg.idle.intervalS() * 1000L);
        this.idleEnabled = "on".equals(memory.setting("idle_enabled").orElse(cfg.idle.enabled() ? "on" : "off"));
        this.combatMode = validCombatMode(memory.setting("combat_mode").orElse(cfg.combat.mode()));
    }

    static String validCombatMode(String m) {
        String v = m == null ? "" : m.strip().toLowerCase();
        return v.equals("off") || v.equals("protect") ? v : "defend";
    }

    /** 任务单 19：网页改了战斗开关——存起来、推给在线的伙伴（不用重连）、状态条回显。 */
    private void setCombatMode(String mode) {
        combatMode = validCombatMode(mode);
        memory.setSetting("combat_mode", combatMode);
        log.info("combat_mode {}", combatMode);
        pushRulesToMc();
        pushStatus(true);
    }

    /** 规则整份重发给模组：白名单 + 怪物名单 + 战斗开关。 */
    private void pushRulesToMc() {
        if (mc == null) {
            return;
        }
        MobRules mobs = knowledge.mobRules();
        mc.send(envelope(MessageType.RULES, Peer.MC, new RulesUpdate(knowledge.blockRules(), mobs, combatMode)));
        log.info("rules_pushed combat_mode={} hostile={}", combatMode, mobs.hostile().size());
    }

    private static String combatOutcomeZh(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "killed" -> "打死了";
            case "fled" -> "怪走了";
            case "gave_up" -> "打不过跑了";
            case "stopped" -> "被叫停了";
            case "died" -> "它死了";
            default -> outcome == null ? "不清楚" : outcome;
        };
    }

    /** 所有模型调用都从这里进 brain 线程，顺便数着"模型忙不忙"（空闲循环只在模型空着时想）。 */
    private void runBrain(Runnable r) {
        brainBusy++;
        brainExec.execute(() -> {
            try {
                r.run();
            } finally {
                submit(() -> brainBusy--);
            }
        });
    }

    public void submit(Runnable r) {
        exec.execute(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.error("hub task failed", e);
            }
        });
    }

    // ---------------- /ws/mc ----------------

    public void mcConnected(Sender s, Hello hello) {
        if (mc != null && mc != s) {
            mc.close(4409, "replaced");
        }
        mc = s;
        mcOnline = true;
        lastSnapshot = null;
        BlockRules rules = knowledge.blockRules();
        MobRules mobs = knowledge.mobRules();
        s.send(envelope(MessageType.HELLO, Peer.MC, HelloOk.mc(now(), rules, mobs, combatMode)));
        log.info("executor online player={} mod={} rules mineable={} placeable={} hostile={} combat_mode={}", hello.player(),
                hello.modVersion(), rules.mineable().size(), rules.placeable().size(), mobs.hostile().size(), combatMode);
        memory.saveEvent("executor_online", null, null, obj("player", hello.player()));
        pushStatus(true);
    }

    public void mcDisconnected(Sender s) {
        if (mc != s) {
            return;
        }
        mc = null;
        mcOnline = false;
        lastSnapshot = null;
        log.info("executor offline");
        memory.saveEvent("executor_offline", null, null, null);
        slot.onExecutorLost().ifPresent(this::handleSynth);
        notice("伙伴掉线了", "warn");
        pushStatus(true);
        maybeWriteEpisodeOnOffline();
    }

    public void mcMessage(Envelope e) {
        switch (e.type()) {
            case SNAPSHOT -> onSnapshot(ProtocolCodec.bodyAs(e, Snapshot.class));
            case EVENT -> onEvent(ProtocolCodec.bodyAs(e, Event.class));
            case ACK -> onAck(ProtocolCodec.bodyAs(e, Ack.class));
            case PROGRESS -> onProgress(ProtocolCodec.bodyAs(e, Progress.class));
            case RESULT -> onResult(ProtocolCodec.bodyAs(e, Result.class));
            default -> log.debug("mc sent {} ignored", e.type());
        }
    }

    private void onSnapshot(Snapshot s) {
        Snapshot prev = lastSnapshot;
        boolean changed = prev == null
                || prev.inWorld() != s.inWorld()
                || !Objects.equals(prev.dim(), s.dim())
                || !Objects.equals(roundHealth(prev), roundHealth(s))
                || (prev.task() == null) != (s.task() == null);
        lastSnapshot = s;
        if (changed) {
            pushStatus(true);
        }
        if (s.inWorld()) {
            // 任务单 17：换了维度记事实（去过下界 / 末地）；背包变了重算一遍阶段
            if (s.dim() != null && (prev == null || !s.dim().equals(prev.dim()))) {
                noteDimension(s.dim());
            }
            if (!Objects.equals(lastInventoryForProgress, s.inventory())) {
                lastInventoryForProgress = s.inventory();
                checkProgress();
            }
        }
    }

    /** 任务单 17：第一次到下界 / 末地时记一条没有坐标的事实（settings 里 fact:visited_nether）。 */
    private void noteDimension(String dim) {
        String fact = Landmarks.DIM_NETHER.equals(dim) ? "visited_nether" : Landmarks.DIM_END.equals(dim) ? "visited_end" : null;
        if (fact == null || memory.setting("fact:" + fact).isPresent()) {
            return;
        }
        memory.setSetting("fact:" + fact, String.valueOf(now()));
        log.info("fact_recorded id={} source=dim_change", fact);
        notice(Landmarks.DIM_END.equals(dim) ? "第一次到末地，记下了" : "第一次到下界，记下了", "info");
        checkProgress();
    }

    /** 任务单 17：世界事实入表。同种在 32 格内已有记录就不重复；自己看见的新地标在网页提示一句。返回是否新记。 */
    private boolean recordFact(String kind, String name, Pos pos, String dim, String source) {
        String d = dim != null ? dim : lastSnapshot != null && lastSnapshot.dim() != null ? lastSnapshot.dim() : "minecraft:overworld";
        Optional<StoredWorldFact> near = memory.nearestFact(kind, d, pos.x(), pos.z(), FACT_DEDUPE_RADIUS);
        if (near.isPresent()) {
            log.info("landmark kind={} source={} new=false near_id={}", kind, source, near.get().id());
            return false;
        }
        StoredWorldFact f = memory.saveWorldFact(kind, name, d, pos.x(), pos.y(), pos.z(), source);
        log.info("landmark kind={} source={} new=true dim={} id={}", kind, source, d, f.id());
        if ("seen".equals(source)) {
            Pos from = lastSnapshot != null ? lastSnapshot.pos() : null;
            notice("它看见了" + Landmarks.describe(f, from, lastSnapshot != null && lastSnapshot.dim() != null ? lastSnapshot.dim() : d), "info");
        }
        checkProgress();
        return true;
    }

    private java.util.Set<String> progressDone() {
        if (progressDone == null) {
            progressDone = new java.util.HashSet<>(memory.progressStages());
        }
        return progressDone;
    }

    private Progression.Assessment assessProgress() {
        Map<String, Integer> inv = lastSnapshot != null && lastSnapshot.inWorld() && lastSnapshot.inventory() != null
                ? lastSnapshot.inventory() : Map.of();
        return knowledge.progression().assess(inv, memory.factIds(), progressDone());
    }

    /** 任务单 17：算一遍阶段，新做完的记进 progress 表并在网页提示；记过的不退回。 */
    private void checkProgress() {
        Progression p = knowledge.progression();
        if (p.isEmpty()) {
            return;
        }
        for (Progression.Stage st : assessProgress().done()) {
            if (progressDone().add(st.id())) {
                memory.saveProgress(st.id(), null);
                log.info("progress_reached stage={}", st.id());
                notice("「" + st.name() + "」这一阶段做完了", "info");
            }
        }
    }

    /** "我们到哪了"的那句话：按通关地图算出来的，不经过模型。 */
    String progressText() {
        Progression p = knowledge.progression();
        String text = p.describe(assessProgress(), names);
        boolean inWorld = lastSnapshot != null && lastSnapshot.inWorld() && companionInWorld();
        return inWorld || p.isEmpty() ? text : "（它现在不在游戏里，背包按空的算，做过的阶段还在账上）" + text;
    }

    /** "记住我们到铁器了"：把那一阶段和它前面的都记成做过（主人自己手里的东西它看不见，所以给他一个口子）。 */
    private String claimProgress(String word) {
        Progression p = knowledge.progression();
        if (p.isEmpty()) {
            return "我还没有通关地图，记不了阶段。";
        }
        String w = word == null ? "" : word.strip().toLowerCase();
        Progression.Stage target = null;
        for (Progression.Stage st : p.stages()) {
            if (st.id().equals(w) || st.name().equals(w)) {
                target = st;
                break;
            }
        }
        if (target == null) {
            return "没听懂是哪个阶段。阶段有：" + p.stages().stream().map(Progression.Stage::name)
                    .collect(java.util.stream.Collectors.joining("、")) + "。";
        }
        int added = 0;
        for (Progression.Stage st : p.stages()) {
            if (progressDone().add(st.id())) {
                memory.saveProgress(st.id(), "owner_said");
                added++;
            }
            if (st == target) {
                break;
            }
        }
        log.info("progress_claimed stage={} added={}", target.id(), added);
        return "记下了，我们到「" + target.name() + "」了。" + progressText();
    }

    /** "记住这里是村庄"：按最新快照的位置记一条世界事实。 */
    private String rememberLandmark(String kind, String word) {
        if (lastSnapshot == null || lastSnapshot.pos() == null || !lastSnapshot.inWorld()) {
            return "我现在不在游戏里，不知道\"这里\"是哪，等我进去了再说一遍。";
        }
        boolean fresh = recordFact(kind, word, lastSnapshot.pos(), lastSnapshot.dim(), "owner_said");
        return (fresh ? "记下了：这里是" : "这儿我记过了，是") + Landmarks.kindName(kind) + "。";
    }

    /** 任务单 23：主人投了眼——near 就是要塞在脚下附近；否则两次隔 100 格以上求交点，说一句并记要塞。 */
    private void onEye(Event ev) {
        if (ev.pos() == null) {
            return;
        }
        String dim = ev.dim() == null ? "minecraft:overworld" : ev.dim();
        if ("near".equals(ev.what())) {
            boolean fresh = recordFact(Landmarks.STRONGHOLD, null, ev.pos(), dim, "task_result");
            log.info("eye near pos=({}, {}) fresh={}", Math.round(ev.pos().x()), Math.round(ev.pos().z()), fresh);
            sayPlain("眼没飞远就掉下来了，要塞就在这脚下附近，往下挖就是。");
            eyeThrows.clear();
            return;
        }
        double yaw = ev.count() == null ? 0 : ev.count();
        eyeThrows.add(new EyeLocator.Throw(ev.pos().x(), ev.pos().z(), yaw, false));
        if (eyeThrows.size() > 6) {
            eyeThrows.remove(0);
        }
        Optional<double[]> p = EyeLocator.estimate(eyeThrows);
        if (p.isEmpty()) {
            if (eyeThrows.size() == 1) {
                sayPlain("记下方向了，往那边走 100 格以上再投一次，我就能算出要塞在哪。");
            } else if (!EyeLocator.lastTwoFarEnough(eyeThrows)) {
                sayPlain("两次投得太近，再走远一点（100 格以上）投一次。");
            } else {
                sayPlain("两次方向差不多，算不出交点，换个方向走远点再投一次。");
            }
            log.info("eye throws={} estimate=none", eyeThrows.size());
            return;
        }
        EyeLocator.Estimate est = EyeLocator.describeFrom(p.get(), ev.pos().x(), ev.pos().z());
        Pos target = new Pos(p.get()[0], 40, p.get()[1]);
        recordFact(Landmarks.STRONGHOLD, null, target, dim, "task_result");
        log.info("eye estimate x={} z={} dist={}", Math.round(est.x()), Math.round(est.z()), Math.round(est.distance()));
        sayPlain("要塞大概在 (" + Math.round(est.x()) + ", " + Math.round(est.z()) + ")，离这儿 " + Math.round(est.distance()) + " 格，往"
                + est.direction() + "。说\"带我去要塞\"我带你过去。");
        eyeThrows.clear();
    }

    /** 任务单 22：门搭好了就把门里那格记成传送门（按当前维度分主世界 / 下界）。 */
    private void rememberPortal(Result r) {
        if (r.status() != dev.blockmate.protocol.ResultStatus.OK || commandNames.get(r.commandId()) != CommandName.BUILD_PORTAL) {
            return;
        }
        Result.BuildPortalDetail d = r.detailAs(Result.BuildPortalDetail.class);
        if (d == null || d.inside() == null) {
            return;
        }
        String dim = lastSnapshot != null && lastSnapshot.dim() != null ? lastSnapshot.dim() : "minecraft:overworld";
        recordFact(Landmarks.DIM_NETHER.equals(dim) ? Landmarks.PORTAL_NETHER : Landmarks.PORTAL_OVERWORLD, null, d.inside(), dim, "task_result");
    }

    /** 任务单 17：挖成功的位置记成矿点（32 格内不重复，不提示）。 */
    private void rememberMineSite(Result r) {
        if (r.status() != dev.blockmate.protocol.ResultStatus.OK || commandNames.get(r.commandId()) != CommandName.MINE
                || lastSnapshot == null || lastSnapshot.pos() == null) {
            return;
        }
        recordFact(Landmarks.MINE_SITE, null, lastSnapshot.pos(), lastSnapshot.dim(), "task_result");
    }

    /** 任务单 21：目的地怎么叫——地标名 > 地点名（home=家、bed=床）> 坐标。 */
    static String leadDestName(JsonObject args) {
        if (args == null) {
            return "目的地";
        }
        if (args.has("landmark") && !args.get("landmark").isJsonNull() && !args.get("landmark").getAsString().isBlank()) {
            return args.get("landmark").getAsString();
        }
        if (args.has("place") && !args.get("place").isJsonNull() && !args.get("place").getAsString().isBlank()) {
            String pl = args.get("place").getAsString();
            return switch (pl) {
                case "home" -> "家";
                case "bed" -> "床";
                default -> pl;
            };
        }
        if (args.has("x") && args.has("y") && args.has("z")) {
            return "（" + Math.round(args.get("x").getAsDouble()) + ", " + Math.round(args.get("y").getAsDouble()) + ", "
                    + Math.round(args.get("z").getAsDouble()) + "）";
        }
        return "目的地";
    }

    /** 伙伴自己说的一句（带路停下）：存消息、发网页，不经过模型。 */
    private void sayPlain(String text) {
        String replyId = UUID.randomUUID().toString();
        replyChunk(replyId, null, text);
        StoredMessage saved = memory.saveMessage("companion", text, null, null);
        replyDone(replyId, null, saved.id());
        log.info("chat_out len={} plain=true msg_id={}", text.length(), saved.id());
    }

    /** 不经过模型、直接回主人一句（任务单 17：进度、记地标）。 */
    private void directReply(String inReplyTo, StoredMessage src, String text) {
        String replyId = UUID.randomUUID().toString();
        replyChunk(replyId, inReplyTo, text);
        StoredMessage saved = memory.saveMessage("companion", text, src.id(), null);
        replyDone(replyId, inReplyTo, saved.id());
        log.info("chat_out len={} direct=true msg_id={}", text.length(), saved.id());
        pushStatus(true);
    }

    private static Long roundHealth(Snapshot s) {
        return s.health() == null ? null : Math.round(s.health());
    }

    private void onEvent(Event ev) {
        if (ev.kind() == null) {
            return;
        }
        switch (ev.kind()) {
            case CHAT -> {
                if (ev.text() != null && !ev.text().isBlank()) {
                    memory.saveMessage("owner_ingame", ev.text(), null, null);
                }
                log.info("event chat from_owner=true len={}", ev.text() == null ? 0 : ev.text().length());
                memory.saveEvent("chat", null, null, obj("len", ev.text() == null ? 0 : ev.text().length()));
            }
            case DEATH -> {
                memory.saveEvent("death", null, null, ProtocolCodec.toBody(ev));
                diedInEnd = Landmarks.DIM_END.equals(ev.dim()) || (lastSnapshot != null && Landmarks.DIM_END.equals(lastSnapshot.dim()));
                notice(diedInEnd ? "伙伴在末地死了" : "伙伴死了", "warn");
            }
            case EYE -> {
                memory.saveEvent("eye", null, null, ProtocolCodec.toBody(ev));
                onEye(ev);
            }
            case DRAGON_FIGHT -> {
                memory.saveEvent("dragon_fight", null, null, ProtocolCodec.toBody(ev));
                int ms = ev.count() == null ? 0 : ev.count();
                int crystals = ev.delta() == null ? 0 : ev.delta();
                String deaths = ev.text() == null ? "0" : ev.text();
                log.info("event dragon_fight outcome={} ms={} crystals={} deaths={}", ev.what(), ms, crystals, deaths);
                String stats = "用了 " + Math.round(ms / 60000.0) + " 分钟，打掉 " + crystals + " 个水晶，我死了 " + deaths + " 次。";
                switch (ev.what() == null ? "" : ev.what()) {
                    case "dragon_dead" -> sayPlain("龙死了！" + stats);
                    case "tired" -> sayPlain("我打不动了，你来主攻，我守着你。" + stats);
                    default -> notice("打龙结束：" + ev.what() + "，" + stats, "info");
                }
            }
            case RESPAWN -> {
                memory.saveEvent("respawn", null, null, ProtocolCodec.toBody(ev));
                notice("伙伴重生了", "info");
                afterRespawn();
            }
            case AUTO_EAT -> {
                memory.saveEvent("auto_eat", null, null, ProtocolCodec.toBody(ev));
                log.info("event auto_eat item={} food_after={}", ev.item(), ev.count());
            }
            case BED_COLLECTED -> {
                memory.saveEvent("bed_collected", null, null, ProtocolCodec.toBody(ev));
                log.info("event bed_collected");
            }
            case LANDMARK -> {
                memory.saveEvent("landmark", null, null, ProtocolCodec.toBody(ev));
                if (ev.what() != null && ev.pos() != null) {
                    recordFact(ev.what(), null, ev.pos(), ev.dim(), "seen");
                }
            }
            case DANGER -> {
                memory.saveEvent("danger", null, null, ProtocolCodec.toBody(ev));
                if ("overwhelmed".equals(ev.what())) {
                    // 任务单 19：打不过了，正往主人（或出生点）跑
                    notice("它打不过，正往你这边跑", "warn");
                    log.info("event danger overwhelmed");
                } else if (ev.pos() != null) {
                    recordFact(Landmarks.DANGER, null, ev.pos(), ev.dim(), "seen");
                }
            }
            case LEAD_PAUSED -> {
                memory.saveEvent("lead_paused", null, null, ProtocolCodec.toBody(ev));
                leadPausedCommandId = planCommandId;
                String why = "cliff".equals(ev.what()) ? "前面是断崖" : "前面有岩浆";
                log.info("event lead_paused reason={} cmd_id={}", ev.what(), planCommandId);
                sayPlain(why + "，我停这儿了，你说\"继续\"我再走。");
            }
            case COMBAT -> {
                memory.saveEvent("combat", null, null, ProtocolCodec.toBody(ev));
                int ms = ev.count() == null ? 0 : ev.count();
                int dmg = ev.delta() == null ? 0 : ev.delta();
                log.info("event combat target={} outcome={} ms={} damage={}", ev.what(), ev.reason(), ms, dmg);
                notice("它和" + MobNames.name(ev.what()) + "打了一架：" + combatOutcomeZh(ev.reason()) + "，用了 "
                        + Math.round(ms / 1000.0) + " 秒，掉了 " + dmg + " 血", "info");
            }
            default -> memory.saveEvent(ev.kind().name().toLowerCase(), null, null, ProtocolCodec.toBody(ev));
        }
        log.info("event kind={}", ev.kind().name().toLowerCase());
        pushStatus(true);
    }

    /** 任务单 15：重生后——主人在就过去找主人，不在但记过家就回家，都没有就待着。等 2 秒让模组进完世界。 */
    private void afterRespawn() {
        if (diedInEnd) {
            // 任务单 23：末地死了，按记过的末地传送门走回去；没记过就说一句
            diedInEnd = false;
            boolean known = memory.worldFacts().stream().anyMatch(f -> Landmarks.END_PORTAL.equals(f.kind()));
            log.info("respawn_decision died_in_end=true end_portal_known={}", known);
            if (!known) {
                sayPlain("我死了一回，现在在出生点，没记过末地传送门在哪，过不去了。");
                return;
            }
            sayPlain("我死了一回，从出生点走回末地传送门去。");
            JsonObject gotoArgs = new JsonObject();
            gotoArgs.addProperty("place", "末地传送门");
            exec.schedule(() -> submit(() -> {
                if (!companionInWorld() || (plan != null && plan.running())) {
                    return;
                }
                startPlan("重生后回末地", List.of(new PlanRun.Step(0, CommandName.GOTO, gotoArgs),
                        new PlanRun.Step(1, CommandName.ENTER_PORTAL, new JsonObject())), null);
            }), 2, java.util.concurrent.TimeUnit.SECONDS);
            return;
        }
        boolean ownerHere = (ownerOnline != null && ownerOnline) || (lastSnapshot != null && lastSnapshot.owner() != null);
        boolean homeKnown = memory.place("home").isPresent();
        CommandName action = ownerHere ? CommandName.COME_HERE : homeKnown ? CommandName.GO_HOME : null;
        log.info("respawn_decision owner_here={} home_known={} action={}", ownerHere, homeKnown, action == null ? "none" : action.wire());
        if (action == null) {
            return;
        }
        exec.schedule(() -> submit(() -> {
            if (!companionInWorld() || (plan != null && plan.running())) {
                return;
            }
            startPlan(action == CommandName.COME_HERE ? "重生后去找主人" : "重生后回家",
                    List.of(new PlanRun.Step(0, action, new JsonObject())), null);
        }), 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void onAck(Ack a) {
        TaskSlot.AckOutcome outcome = slot.onAck(a.commandId(), a.accepted());
        memory.saveEvent("ack", a.commandId(), commandSource.get(a.commandId()), ProtocolCodec.toBody(a));
        log.info("ack cmd_id={} accepted={} outcome={}", a.commandId(), a.accepted(), outcome);
        if (outcome == TaskSlot.AckOutcome.REJECTED) {
            CommandName name = nameOf(a.commandId());
            Result failed = Result.failed(a.commandId(), a.reason() == null ? dev.blockmate.protocol.Reason.REJECTED : a.reason());
            if (a.commandId().equals(planCommandId)) {
                onStepResult(failed);
            } else {
                say(name, failed);
            }
        }
        pushStatus(true);
    }

    private final Map<String, CommandName> commandNames = new HashMap<>();

    private CommandName nameOf(String commandId) {
        return commandNames.getOrDefault(commandId, CommandName.REPORT_STATUS);
    }

    private void onProgress(Progress p) {
        memory.saveEvent("progress", p.commandId(), null, ProtocolCodec.toBody(p));
    }

    private void onResult(Result r) {
        TaskSlot.ResultOutcome outcome = slot.onResult(r.commandId());
        memory.saveEvent("result", r.commandId(), commandSource.get(r.commandId()), ProtocolCodec.toBody(r));
        log.info("result cmd_id={} status={} reason={} outcome={}", r.commandId(), r.status(), r.reason(), outcome);
        rememberBedIfSlept(r);
        rememberMineSite(r);
        rememberPortal(r);
        String stopSource = stopCommands.remove(r.commandId());
        if (stopSource != null) {
            if ("button".equals(stopSource)) {
                notice(r.status() == dev.blockmate.protocol.ResultStatus.OK ? "已停下" : "没停住：" + Brain.describeResult(CommandName.STOP, r), "info");
            } else {
                say(CommandName.STOP, r);
            }
            commandNames.remove(r.commandId());
            pushStatus(true);
            return;
        }
        if (r.commandId().equals(planCommandId)) {
            onStepResult(r);
        } else if (outcome == TaskSlot.ResultOutcome.FINISHED) {
            say(nameOf(r.commandId()), r);
        }
        if (outcome != TaskSlot.ResultOutcome.UNKNOWN) {
            commandNames.remove(r.commandId());
        }
        leadDest.remove(r.commandId());
        if (r.commandId().equals(leadPausedCommandId)) {
            leadPausedCommandId = null;
        }
        pushStatus(true);
    }

    private void handleSynth(TaskSlot.Synth s) {
        Result r = Result.failed(s.commandId(), s.reason());
        memory.saveEvent("result", s.commandId(), s.sourceMessageId(), ProtocolCodec.toBody(r));
        log.info("result_synth cmd_id={} reason={}", s.commandId(), s.reason());
        commandNames.remove(s.commandId());
        if (s.commandId().equals(planCommandId)) {
            onStepResult(r);
        } else {
            say(s.name(), r);
        }
        pushStatus(true);
    }

    // ---------------- 计划（任务单 10） ----------------

    /** 任务单 18：由提议发起的计划 source 记 idle。 */
    public void startPlan(String goal, List<PlanRun.Step> steps, String sourceMessageId, String source) {
        pendingPlanSource = source == null ? "owner" : source;
        startPlan(goal, steps, sourceMessageId);
    }

    /** 开始一个计划；有在跑的旧计划就按 aborted 结束它（模组那边新命令会取消旧命令）。 */
    public void startPlan(String goal, List<PlanRun.Step> steps, String sourceMessageId) {
        if (plan != null && plan.running()) {
            plan.abort(PlanRun.Outcome.ABORTED, now());
            finishPlan();
        }
        planSource = pendingPlanSource;
        pendingPlanSource = "owner";
        awaitingStep = null;
        plan = new PlanRun(UUID.randomUUID().toString(), goal == null ? "" : goal, steps, sourceMessageId, now());
        log.info("plan_start plan_id={} steps={}", plan.planId(), steps.size());
        dispatchNext();
    }

    public Optional<PlanRun> currentPlan() {
        return Optional.ofNullable(plan);
    }

    /** 测试用：当前步的 command id。 */
    public String planCommandIdForTest() {
        return planCommandId;
    }

    /** 测试用：最新快照。 */
    public Optional<Snapshot> lastSnapshotForTest() {
        return Optional.ofNullable(lastSnapshot);
    }

    private void dispatchNext() {
        Optional<PlanRun.Step> next = plan.advance();
        if (next.isEmpty()) {
            if (plan.running()) {
                plan.abort(PlanRun.Outcome.DONE, now());
                finishPlan();
            }
            return;
        }
        PlanRun.Step step = next.get();
        JsonObject args = PlanValidator.normalizeForDispatch(step.command(), step.args(), knowledge.blockRules());
        log.info("plan_step plan_id={} index={} command={} state=running", plan.planId(), step.index(), step.command().wire());
        if (step.command() == CommandName.REMEMBER_PLACE) {
            rememberPlace(step, args);
            return;
        }
        if (step.command() == CommandName.ASK_OWNER) {
            // 任务单 22：铁规矩 4——停下问主人，不下发
            String say = args.has("say") && !args.get("say").isJsonNull() && !args.get("say").getAsString().isBlank()
                    ? args.get("say").getAsString() : "要我继续吗？";
            awaitingStep = step;
            awaitingSince = now();
            log.info("ask_owner plan_id={} index={}", plan.planId(), step.index());
            memory.saveEvent("ask_owner", null, plan.sourceMessageId(), obj("say", say));
            sayPlain(say);
            pushPlan();
            return;
        }
        if (step.command() == CommandName.BUILD_PORTAL) {
            // 任务单 22：图纸随命令发，模组不读文件
            Blueprint bp = knowledge.blueprint("nether_portal");
            if (bp == null) {
                log.info("build_portal: no blueprint -> stuck");
                synthStep(step, id -> Result.failed(id, Reason.STUCK, new Result.BuildPortalDetail(null, 0, "no_blueprint")));
                return;
            }
            args = bp.toArgs();
        }
        if (step.command() == CommandName.FIGHT) {
            // 任务单 20：铁规矩 5，不在能打名单上的不下发（模组那边还会再查一遍）
            String kind = args.has("kind") && !args.get("kind").isJsonNull() ? Knowledge.normalizeId(args.get("kind").getAsString()) : "";
            if (kind.isEmpty() || !knowledge.mobRules().hostile().contains(kind)) {
                log.info("fight_not_allowed kind={}", kind);
                synthStep(step, id -> Result.failed(id, Reason.NOT_ALLOWED));
                return;
            }
            args.addProperty("kind", kind);
        }
        CommandName wire = step.command();
        String factKind = null;
        if (step.command() == CommandName.GO_HOME || (step.command() == CommandName.GOTO && !hasPos(args))
                || (step.command() == CommandName.LEAD && !hasPos(args))) {
            String placeName = step.command() == CommandName.GO_HOME ? "home"
                    : normalizePlaceName(args.has("place") && !args.get("place").isJsonNull() ? args.get("place").getAsString() : "");
            Optional<StoredPlace> p = memory.place(placeName);
            String hereDim = lastSnapshot != null ? lastSnapshot.dim() : null;
            if (p.isEmpty() && lastSnapshot != null && lastSnapshot.pos() != null) {
                // 任务单 17：地点表里没有就查地标，"去村庄"= 最近的村庄
                String kind = Landmarks.kindOf(placeName, hereDim);
                if (kind != null) {
                    Optional<StoredWorldFact> f = memory.nearestFact(kind, hereDim != null ? hereDim : "minecraft:overworld",
                            lastSnapshot.pos().x(), lastSnapshot.pos().z(), Double.MAX_VALUE);
                    if (f.isPresent()) {
                        p = Optional.of(new StoredPlace(placeName, f.get().dim(), f.get().x(), f.get().y(), f.get().z(), f.get().ts()));
                        factKind = kind;
                        log.info("place_from_fact name={} kind={} fact_id={}", placeName, kind, f.get().id());
                    }
                }
            }
            if (p.isEmpty() || (hereDim != null && p.get().dim() != null && !hereDim.equals(p.get().dim()))) {
                log.info("place_unknown name={} known={} same_dim={}", placeName, p.isPresent(), p.isPresent() && hereDim == null);
                synthStep(step, id -> Result.failed(id, Reason.NO_PLACE_KNOWN));
                return;
            }
            JsonObject g = new JsonObject();
            g.addProperty("x", p.get().x());
            g.addProperty("y", p.get().y());
            g.addProperty("z", p.get().z());
            g.addProperty("place", placeName);
            if (step.command() == CommandName.LEAD) {
                // 任务单 21：带路保留 lead，顺便带上地标的中文名，到了好说"到村庄了"
                if (factKind != null) {
                    g.addProperty("landmark", Landmarks.kindName(factKind));
                }
                wire = CommandName.LEAD;
            } else {
                wire = CommandName.GOTO;
            }
            args = g;
            log.info("place_resolved name={} for={}", placeName, step.command().wire());
        }
        planCommandId = issueCommand(wire, args, plan.sourceMessageId());
        if (step.command() == CommandName.LEAD && planCommandId != null) {
            leadDest.put(planCommandId, leadDestName(args));
        }
        pushPlan();
    }

    private static boolean hasPos(JsonObject a) {
        return a.has("x") && a.has("y") && a.has("z") && !a.get("x").isJsonNull() && !a.get("y").isJsonNull()
                && !a.get("z").isJsonNull();
    }

    /** "家" / "home" / "Home " 都算 home，"床" 算 bed，其余小写去空格。 */
    static String normalizePlaceName(String raw) {
        String n = raw == null ? "" : raw.strip().toLowerCase();
        return switch (n) {
            case "家", "home", "家里", "我家" -> "home";
            case "床", "bed" -> "bed";
            default -> n;
        };
    }

    /** 任务单 15：睡成功就把床的位置记成 bed；没记过家的话顺手记成 home（任务单 13 留的）。 */
    private void rememberBedIfSlept(Result r) {
        if (r.status() != dev.blockmate.protocol.ResultStatus.OK || commandNames.get(r.commandId()) != CommandName.SLEEP) {
            return;
        }
        Result.SleepDetail d = r.detailAs(Result.SleepDetail.class);
        if (d == null || !d.slept() || d.bedPos() == null) {
            return;
        }
        String dim = lastSnapshot != null && lastSnapshot.dim() != null ? lastSnapshot.dim() : "minecraft:overworld";
        memory.savePlace("bed", dim, d.bedPos().x(), d.bedPos().y(), d.bedPos().z());
        boolean homeSet = false;
        if (memory.place("home").isEmpty()) {
            memory.savePlace("home", dim, d.bedPos().x(), d.bedPos().y(), d.bedPos().z());
            homeSet = true;
        }
        log.info("place_remembered name=bed dim={} home_set={}", dim, homeSet);
    }

    /** remember_place 由 hub 自己完成（任务单 13）：拿最新快照的位置存进 places 表，不下发模组。 */
    private void rememberPlace(PlanRun.Step step, JsonObject args) {
        String name = normalizePlaceName(args.has("name") && !args.get("name").isJsonNull() ? args.get("name").getAsString() : "");
        if (name.isEmpty()) {
            name = "home";
        }
        if (lastSnapshot == null || lastSnapshot.pos() == null || !lastSnapshot.inWorld()) {
            log.info("place_remember_failed name={} reason=no_snapshot", name);
            synthStep(step, id -> Result.failed(id, Reason.NO_PLACE_KNOWN));
            return;
        }
        Pos pos = lastSnapshot.pos();
        String dim = lastSnapshot.dim() == null ? "minecraft:overworld" : lastSnapshot.dim();
        memory.savePlace(name, dim, pos.x(), pos.y(), pos.z());
        log.info("place_remembered name={} dim={}", name, dim);
        String finalName = name;
        synthStep(step, id -> Result.ok(id, new Result.RememberPlaceDetail(finalName, pos)));
    }

    /** hub 自己完成的一步：不发模组，直接合成结果走计划流程（像模组立刻回了 result 一样）。 */
    private void synthStep(PlanRun.Step step, java.util.function.Function<String, Result> make) {
        String id = UUID.randomUUID().toString();
        commandNames.put(id, step.command());
        if (plan.sourceMessageId() != null) {
            commandSource.put(id, plan.sourceMessageId());
        }
        planCommandId = id;
        Result r = make.apply(id);
        memory.saveEvent("command", id, plan.sourceMessageId(), obj("name", step.command().wire()));
        memory.saveEvent("result", id, plan.sourceMessageId(), ProtocolCodec.toBody(r));
        log.info("command name={} cmd_id={} synth=hub", step.command().wire(), id);
        log.info("result_synth cmd_id={} status={} reason={}", id, r.status(), r.reason());
        pushPlan();
        submit(() -> onStepResult(r));
    }

    /** 当前步的结果（真的或 hub 合成的）。 */
    private void onStepResult(Result r) {
        if (plan == null || !plan.running()) {
            return; // 计划已停或已换，迟到的结果不管
        }
        PlanRun.Step step = plan.currentStep().orElse(null);
        if (step == null) {
            return;
        }
        if (r.status() == dev.blockmate.protocol.ResultStatus.CANCELLED) {
            return; // 被 stop 或新计划替换，那边已经处理
        }
        planCommandId = null;
        if (r.status() == dev.blockmate.protocol.ResultStatus.OK) {
            boolean done = plan.stepDone(r);
            log.info("plan_step plan_id={} index={} command={} state=done", plan.planId(), step.index(), step.command().wire());
            if (done) {
                String goal = plan.size() > 1 ? plan.goal() : null;
                if (goal == null && step.command() == CommandName.LEAD) {
                    goal = "带主人去" + leadDest.getOrDefault(r.commandId(), "目的地"); // 任务单 21：到了说"到村庄了"
                }
                finishPlan();
                say(step.command(), r, goal);
            } else {
                pushPlan();
                dispatchNext();
            }
            return;
        }
        plan.stepFailed(r);
        log.info("plan_step plan_id={} index={} command={} state=failed reason={}", plan.planId(), step.index(),
                step.command().wire(), r.reason());
        pushPlan();
        if (plan.size() == 1) {
            plan.abort(PlanRun.Outcome.FAILED, now());
            finishPlan();
            say(step.command(), r);
            return;
        }
        String planId = plan.planId();
        BrainContext ctx = context();
        String goal = plan.goal();
        int size = plan.size();
        List<String> doneNames = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            if (plan.state(i) == dev.blockmate.protocol.PlanStepState.DONE) {
                doneNames.add(SnapshotSummary.taskName(plan.steps().get(i).command()));
            }
        }
        String doneSummary = doneNames.isEmpty() ? "" : "已做完：" + String.join("、", doneNames);
        int retriesLeft = PlanRun.MAX_RETRIES - plan.retries();
        runBrain(() -> {
            Brain.PlanDecision d;
            try {
                d = brain.planFailure(goal, step.index(), size, step.command(), r, doneSummary, retriesLeft, ctx);
            } catch (ModelException ex) {
                log.warn("model_fail kind={} msg={} (plan_failure)", ex.kind(), ex.getMessage());
                d = new Brain.PlanDecision(Brain.PlanDecision.ABORT, "", null);
            }
            Brain.PlanDecision decision = d;
            submit(() -> applyDecision(planId, step, r, decision));
        });
    }

    /** 四选一（任务单 11）：先把脑子的那句话说给主人，再按它说的做。 */
    private void applyDecision(String planId, PlanRun.Step step, Result r, Brain.PlanDecision decision) {
        if (plan == null || !plan.planId().equals(planId) || !plan.running()) {
            return;
        }
        String say = decision.say() == null ? "" : decision.say().strip();
        log.info("plan_decision plan_id={} index={} action={} say_len={}", planId, step.index(), decision.action(), say.length());
        switch (decision.action()) {
            case Brain.PlanDecision.RETRY -> {
                if (plan.retryCurrent()) {
                    sayDecision(say.isBlank() ? "这步没成，我再试一次。" : say);
                    planCommandId = issueCommand(step.command(), step.args(), plan.sourceMessageId());
                    log.info("plan_step plan_id={} index={} command={} state=running retry={}", planId, step.index(),
                            step.command().wire(), plan.retries());
                    pushPlan();
                    return;
                }
                sayDecision((say.isBlank() ? "" : say + " ") + "重试的次数用完了，这件事先放下。");
                abortQuietly(PlanRun.Outcome.FAILED);
            }
            case Brain.PlanDecision.SKIP -> {
                sayDecision(say.isBlank() ? "这步跳过，接着做下一步。" : say);
                plan.skipCurrent();
                log.info("plan_step plan_id={} index={} command={} state=skipped", planId, step.index(), step.command().wire());
                pushPlan();
                abortIfNothingLeftElseDispatch(step, r);
            }
            case Brain.PlanDecision.REPLAN -> {
                List<String> errors = new ArrayList<>();
                List<PlanRun.Step> fresh = decision.newSteps() == null ? List.of()
                        : PlanValidator.validateSteps(decision.newSteps(), step.index() + 1, errors, knowledge.blockRules(), knowledge.mobRules());
                if (fresh.isEmpty() || !errors.isEmpty()) {
                    log.info("plan_replan_rejected plan_id={} errors={}", planId, errors);
                    sayDecision((say.isBlank() ? "" : say + " ") + "不过新步骤我没列对，这件事先放下。");
                    abortQuietly(PlanRun.Outcome.FAILED);
                    return;
                }
                sayDecision(say.isBlank() ? "换个法子接着做。" : say);
                plan.replan(fresh);
                log.info("plan_replan plan_id={} from_index={} new_steps={}", planId, step.index() + 1, fresh.size());
                pushPlan();
                dispatchNext();
            }
            default -> {
                if (say.isBlank()) {
                    abortPlan(PlanRun.Outcome.FAILED, step, r);
                } else {
                    sayDecision(say);
                    abortQuietly(PlanRun.Outcome.FAILED);
                }
            }
        }
    }

    /** 结束计划但不再让脑子说话（四选一里已经说过了）。 */
    private void abortQuietly(PlanRun.Outcome how) {
        plan.abort(how, now());
        finishPlan();
    }

    /** 四选一时脑子那句"我打算怎么办"直接发给主人并存消息。 */
    private void sayDecision(String text) {
        String source = plan != null ? plan.sourceMessageId() : null;
        String replyId = UUID.randomUUID().toString();
        replyChunk(replyId, source, text);
        StoredMessage saved = memory.saveMessage("companion", text, source, null);
        replyDone(replyId, source, saved.id());
        log.info("chat_out len={} plan_decision=true msg_id={}", text.length(), saved.id());
    }

    private void abortIfNothingLeftElseDispatch(PlanRun.Step failedStep, Result r) {
        // skipCurrent 之后：还有 pending 的步就派下一步，没有了就按失败结束
        boolean anyPending = false;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.state(i) == dev.blockmate.protocol.PlanStepState.PENDING) {
                anyPending = true;
                break;
            }
        }
        if (anyPending) {
            dispatchNext();
        } else {
            abortPlan(PlanRun.Outcome.FAILED, failedStep, r);
        }
    }

    private void abortPlan(PlanRun.Outcome how, PlanRun.Step step, Result r) {
        plan.abort(how, now());
        finishPlan();
        say(step.command(), r);
    }

    /** "停"或超时：当前计划按 aborted / timeout 结束，剩余步 skipped。 */
    private void abortPlanOnStop(PlanRun.Outcome how) {
        if (plan == null || !plan.running()) {
            return;
        }
        boolean multi = plan.size() > 1;
        plan.abort(how, now());
        finishPlan();
        if (multi) {
            notice(how == PlanRun.Outcome.TIMEOUT ? "这件事做了太久，整个停掉了" : "这件事停了，剩下的步骤不做了", "info");
        }
    }

    private void finishPlan() {
        if (plan == null) {
            return;
        }
        log.info("plan_end plan_id={} outcome={} steps={}", plan.planId(), plan.outcome().name().toLowerCase(), plan.size());
        planCommandId = null;
        boolean worthRemembering = plan.size() > 1 || (plan.steps().get(0).command() != CommandName.REPORT_STATUS
                && plan.steps().get(0).command() != CommandName.REMEMBER_PLACE);
        if (worthRemembering) {
            memory.savePlan(new StoredPlan(plan.planId(), plan.goal(), plan.stepsJson(), plan.outcome().name().toLowerCase(),
                    plan.startedAt(), plan.endedAt() > 0 ? plan.endedAt() : now(), plan.sourceMessageId(), planSource));
        }
        pushPlan();
    }

    private void pushPlan() {
        if (plan != null) {
            broadcast(envelope(MessageType.PLAN, Peer.UI, plan.toProtocol()));
        }
    }

    // ---------------- /ws/ui ----------------

    public void uiConnected(Sender s) {
        uis.add(s);
        s.send(envelope(MessageType.HELLO, Peer.UI,
                new HelloOk(true, now(), ProtocolCodec.VERSION, null, status(), historyJson(), null, null)));
        log.info("ui connected count={}", uis.size());
        pushPreferences(s);
        if (plan != null && plan.running()) {
            s.send(envelope(MessageType.PLAN, Peer.UI, plan.toProtocol()));
        }
    }

    public void uiDisconnected(Sender s) {
        uis.remove(s);
        log.info("ui disconnected count={}", uis.size());
    }

    public void uiMessage(Sender s, Envelope e) {
        switch (e.type()) {
            case CHAT -> onChat(e);
            case STOP -> onStopButton();
            case PREFERENCE_ACTION -> {
                PreferenceAction pa = ProtocolCodec.bodyAs(e, PreferenceAction.class);
                boolean ok = memory.decidePreference(pa.id(), pa.action());
                log.info("preference_action id={} action={} ok={}", pa.id(), pa.action(), ok);
                pushPreferences(null);
            }
            case KEEPER_CONTROL -> keeperControl(ProtocolCodec.bodyAs(e, KeeperControl.class).action());
            case IDLE_CONTROL -> setIdleEnabled(ProtocolCodec.bodyAs(e, IdleControl.class).enabled());
            case COMBAT_CONTROL -> setCombatMode(ProtocolCodec.bodyAs(e, CombatControl.class).mode());
            default -> log.debug("ui sent {} ignored", e.type());
        }
    }

    private void onChat(Envelope env) {
        Chat chat = ProtocolCodec.bodyAs(env, Chat.class);
        if (chat.text() == null || chat.text().isBlank()) {
            return;
        }
        String text = chat.text().strip();
        StoredMessage msg = memory.saveMessage("owner", text, null, null);
        String replyId = UUID.randomUUID().toString();
        // 任务单 18：上一条是它的提议且在 2 分钟内，"好 / 不"就是对提议说的；主人说了话也解除安静模式
        long nowTs = now();
        Optional<IdlePolicy.Proposal> pending = idle.pendingWithin(nowTs);
        boolean wasQuiet = idle.quiet();
        idle.ownerSpoke(nowTs);
        if (wasQuiet) {
            log.info("idle_quiet_mode off");
        }
        if (pending.isPresent() && Shortcuts.isAccept(text)) {
            IdlePolicy.Proposal pr = idle.accepted();
            log.info("chat_in len={} msg_id={} shortcut=idle_accept", text.length(), msg.id());
            memory.saveEvent("idle_accepted", null, msg.id(), obj("goal", pr.goal()));
            PlanValidator.Validated v = PlanValidator.validate(makePlanArgs(pr.goal(), pr.steps()), knowledge.blockRules(), knowledge.mobRules());
            if (v.ok() && companionInWorld()) {
                directReply(env.id(), msg, "好嘞，这就去。");
                startPlan(pr.goal(), v.steps(), msg.id(), "idle");
            } else {
                directReply(env.id(), msg, "刚才那个现在做不了了（" + (companionInWorld() ? String.join("；", v.errors()) : "我不在游戏里") + "）。");
            }
            return;
        }
        if (pending.isPresent() && Shortcuts.isReject(text)) {
            boolean quiet = idle.rejected(nowTs);
            log.info("chat_in len={} msg_id={} shortcut=idle_reject", text.length(), msg.id());
            log.info("idle_rejected goal_hash={} consecutive={}", IdlePolicy.goalKey(pending.get().goal()).hashCode(), idle.consecutiveRejects());
            memory.saveEvent("idle_rejected", null, msg.id(), obj("goal", pending.get().goal()));
            if (quiet) {
                log.info("idle_quiet_mode on");
            }
            directReply(env.id(), msg, quiet ? "好，那我不提了，你想干什么跟我说。" : "好，那不提了。");
            return;
        }
        // 任务单 22：计划停在"问主人"那一步——"点 / 好 / 去"继续，"不 / 别"整个作废；别的话照旧交给模型
        if (awaitingStep != null && plan != null && plan.running()) {
            if (Shortcuts.isAskYes(text)) {
                PlanRun.Step st = awaitingStep;
                awaitingStep = null;
                log.info("chat_in len={} msg_id={} shortcut=ask_owner_yes", text.length(), msg.id());
                memory.saveEvent("ask_owner_yes", null, msg.id(), null);
                directReply(env.id(), msg, "好嘞。");
                synthStep(st, Result::ok);
                return;
            }
            if (Shortcuts.isReject(text)) {
                awaitingStep = null;
                log.info("chat_in len={} msg_id={} shortcut=ask_owner_no", text.length(), msg.id());
                memory.saveEvent("ask_owner_no", null, msg.id(), null);
                directReply(env.id(), msg, "好，那先不做了。");
                abortQuietly(PlanRun.Outcome.ABORTED);
                return;
            }
        }
        // 任务单 23："进末地"：先把能做不能做的说清（问主人步），你说进再进
        if (Shortcuts.isGoEnd(text)) {
            log.info("chat_in len={} msg_id={} shortcut=go_end", text.length(), msg.id());
            if (!companionInWorld()) {
                directReply(env.id(), msg, NOTICE_OFFLINE + "。");
                return;
            }
            Map<String, Integer> inv = lastSnapshot != null && lastSnapshot.inventory() != null ? lastSnapshot.inventory() : Map.of();
            boolean bow = inv.getOrDefault("minecraft:bow", 0) > 0;
            int arrows = inv.entrySet().stream().filter(e -> e.getKey().endsWith("arrow")).mapToInt(Map.Entry::getValue).sum();
            String brief = "进末地前先说清：" + (bow && arrows > 0 ? "我有弓和 " + arrows + " 根箭，能打柱子上的水晶，笼子里的得你打；"
                    : (bow ? "我有弓没箭" : "我没弓") + "，水晶得你打，我只守着你；")
                    + "龙飞着我打不到，俯冲下来我砍；血少我躲柱子后面吃东西；你死了我守着传送门等你；我死了会从出生点走回来。进吗？";
            JsonObject ask = new JsonObject();
            ask.addProperty("say", brief);
            directReply(env.id(), msg, "好。");
            startPlan("进末地", List.of(new PlanRun.Step(0, CommandName.ASK_OWNER, ask),
                    new PlanRun.Step(1, CommandName.ENTER_PORTAL, new JsonObject())), msg.id());
            return;
        }
        // 任务单 22："我们去下界"固定四步：搭门 → 问主人 → 点火 → 进门；材料不够先说缺什么
        if (Shortcuts.isGoNether(text)) {
            log.info("chat_in len={} msg_id={} shortcut=go_nether", text.length(), msg.id());
            if (!companionInWorld()) {
                directReply(env.id(), msg, NOTICE_OFFLINE + "。");
                return;
            }
            Map<String, Integer> inv = lastSnapshot != null && lastSnapshot.inventory() != null ? lastSnapshot.inventory() : Map.of();
            int obsidian = inv.getOrDefault("minecraft:obsidian", 0);
            int flint = inv.getOrDefault("minecraft:flint_and_steel", 0);
            if (obsidian < 10 || flint < 1) {
                String lack = (obsidian < 10 ? "黑曜石现在 " + obsidian + " 块，还差 " + (10 - obsidian) + " 块" : "")
                        + (obsidian < 10 && flint < 1 ? "，" : "") + (flint < 1 ? "没有打火石" : "");
                directReply(env.id(), msg, "去下界要 10 块黑曜石和 1 个打火石：" + lack + "，凑齐了再叫我。");
                return;
            }
            JsonObject ask = new JsonObject();
            ask.addProperty("say", "门搭好了，点吗？");
            directReply(env.id(), msg, "好，材料够。我在脚边搭门，搭好问你点不点。");
            startPlan("去下界", List.of(new PlanRun.Step(0, CommandName.BUILD_PORTAL, new JsonObject()),
                    new PlanRun.Step(1, CommandName.ASK_OWNER, ask), new PlanRun.Step(2, CommandName.LIGHT_PORTAL, new JsonObject()),
                    new PlanRun.Step(3, CommandName.ENTER_PORTAL, new JsonObject())), msg.id());
            return;
        }
        // 任务单 21：带路因危险停着，主人说"继续"就下 continue；别的时候这几个词交给模型
        if (Shortcuts.isContinue(text) && leadPausedCommandId != null && mc != null && plan != null && plan.running()) {
            Envelope cont = envelope(MessageType.COMMAND, Peer.MC, Command.of(CommandName.CONTINUE));
            mc.send(cont);
            memory.saveEvent("command", cont.id(), msg.id(), obj("name", "continue"));
            log.info("chat_in len={} msg_id={} shortcut=continue lead_cmd={}", text.length(), msg.id(), leadPausedCommandId);
            leadPausedCommandId = null;
            directReply(env.id(), msg, "好，接着走。");
            return;
        }
        // 任务单 17：进度问句、"记住我们到 xx 了"、"记住这里是 xx"都不经过模型
        if (Shortcuts.isProgressQuestion(text)) {
            log.info("chat_in len={} msg_id={} shortcut=progress", text.length(), msg.id());
            directReply(env.id(), msg, progressText());
            return;
        }
        Optional<String> claim = Shortcuts.progressClaim(text);
        if (claim.isPresent()) {
            log.info("chat_in len={} msg_id={} shortcut=progress_claim", text.length(), msg.id());
            directReply(env.id(), msg, claimProgress(claim.get()));
            return;
        }
        Optional<CommandName> shortcut = Shortcuts.match(text);
        if (shortcut.isEmpty()) {
            Optional<String> here = Shortcuts.rememberHere(text);
            if (here.isPresent()) {
                String kind = Landmarks.kindOf(here.get(), lastSnapshot != null ? lastSnapshot.dim() : null);
                if (kind != null) {
                    log.info("chat_in len={} msg_id={} shortcut=remember_landmark kind={}", text.length(), msg.id(), kind);
                    directReply(env.id(), msg, rememberLandmark(kind, here.get()));
                    return;
                }
                // 认不出是哪种地标：当普通地点名记进 places，和"记住这里是家"一条路
                log.info("chat_in len={} msg_id={} shortcut=remember_place", text.length(), msg.id());
                JsonObject placeArgs = new JsonObject();
                placeArgs.addProperty("name", normalizePlaceName(here.get()));
                ToolCall placeCall = new ToolCall("shortcut-" + replyId.substring(0, 8), CommandName.REMEMBER_PLACE.wire(), placeArgs);
                onDecision(replyId, env.id(), msg, new ModelReply("", List.of(placeCall), 0, 0, 0), 0, "");
                return;
            }
        }
        if (shortcut.isPresent()) {
            // 固定短语：不经过模型，直接当作模型调了这个工具（动作结束后照旧让它说一句）
            log.info("chat_in len={} msg_id={} shortcut={}", text.length(), msg.id(), shortcut.get().wire());
            ToolCall call = new ToolCall("shortcut-" + replyId.substring(0, 8), shortcut.get().wire(), Shortcuts.argsFor(shortcut.get()));
            onDecision(replyId, env.id(), msg, new ModelReply("", List.of(call), 0, 0, 0), 0, "");
            return;
        }
        log.info("chat_in len={} msg_id={}", text.length(), msg.id());
        BrainContext ctx = context();
        runBrain(() -> {
            try {
                ModelReply reply = brain.decide(text, ctx, chunk -> submit(() -> replyChunk(replyId, env.id(), chunk)));
                submit(() -> onDecision(replyId, env.id(), msg, reply, 0, ""));
            } catch (ModelException ex) {
                submit(() -> onModelFailure(replyId, env.id(), ex));
            }
        });
    }

    /** 模型给的计划不合法时最多再问一次（任务单 11）。 */
    static final int PLAN_REASK_MAX = 1;

    /**
     * @param planAttempt 第几次拿计划（0 = 第一次；不合法重问后是 1）
     * @param prefix      重问前已经流给网页的文字，存消息时拼在前面
     */
    private void onDecision(String replyId, String inReplyTo, StoredMessage src, ModelReply reply, int planAttempt,
                            String prefix) {
        modelOk = true;
        String toolJson = reply.hasToolCalls() ? ProtocolCodec.gson().toJson(reply.toolCalls()) : null;
        List<String> notes = new ArrayList<>();
        boolean actionIssued = false;
        for (ToolCall tc : reply.toolCalls()) {
            if (Tools.MAKE_PLAN.equals(tc.name())) {
                if (actionIssued) {
                    notes.add("一次只做一件，先做第一个。");
                    continue;
                }
                if (!companionInWorld()) {
                    notes.add(NOTICE_OFFLINE + "。");
                    log.info("action_while_offline name=make_plan");
                    continue;
                }
                PlanValidator.Validated v = PlanValidator.validate(tc.arguments(), knowledge.blockRules(), knowledge.mobRules());
                if (v.ok()) {
                    log.info("plan_accepted steps={} attempt={}", v.steps().size(), planAttempt);
                    startPlan(v.goal().isBlank() ? src.text() : v.goal(), v.steps(), src.id());
                    actionIssued = true;
                    continue;
                }
                log.info("plan_rejected attempt={} errors={}", planAttempt, v.errors());
                if (planAttempt < PLAN_REASK_MAX) {
                    reaskPlan(replyId, inReplyTo, src, planAttempt + 1, prefix + reply.text().strip(), v.errors());
                    return;
                }
                notes.add("这件事我没想清楚怎么做（" + String.join("；", v.errors().subList(0, Math.min(2, v.errors().size())))
                        + "），换个说法再跟我说一遍？");
                continue;
            }
            if (Tools.REMEMBER.equals(tc.name())) {
                String t = tc.arguments() != null && tc.arguments().has("text") ? tc.arguments().get("text").getAsString() : "";
                if (!t.isBlank()) {
                    memory.addPendingPreference(t.strip(), src.id());
                    log.info("preference_pending len={}", t.length());
                    pushPreferences(null);
                }
            } else if (Tools.REPORT_PROGRESS.equals(tc.name())) {
                // 任务单 17：算出来的那句接在模型的引子后面
                notes.add(progressText());
                log.info("report_progress");
            } else if (Tools.isAction(tc.name())) {
                if (actionIssued) {
                    notes.add("一次只做一件，先做第一个。");
                    continue;
                }
                CommandName name = Tools.commandOf(tc.name());
                if (name == null) {
                    continue;
                }
                if (!companionInWorld()) {
                    notes.add(NOTICE_OFFLINE + "。");
                    log.info("action_while_offline name={}", name.wire());
                    continue;
                }
                if (name == CommandName.STOP) {
                    abortPlanOnStop(PlanRun.Outcome.ABORTED);
                    issueCommand(name, tc.arguments(), src.id());
                } else {
                    startPlan(src.text(), List.of(new PlanRun.Step(0, name, tc.arguments())), src.id());
                }
                actionIssued = true;
            } else {
                log.warn("unknown tool {}", tc.name());
            }
        }
        String text = reply.text().strip();
        if (!prefix.isBlank()) {
            // 重问的那次没有流式发：和第一次说的一样就不再发，不一样才补发第二句
            if (text.isEmpty() || text.equals(prefix)) {
                text = prefix;
            } else {
                replyChunk(replyId, inReplyTo, "\n" + text);
                text = prefix + "\n" + text;
            }
        }
        if (!notes.isEmpty()) {
            String extra = String.join("", notes);
            replyChunk(replyId, inReplyTo, text.isEmpty() ? extra : "\n" + extra);
            text = text.isEmpty() ? extra : text + "\n" + extra;
        }
        StoredMessage saved = memory.saveMessage("companion", text, src.id(), toolJson);
        replyDone(replyId, inReplyTo, saved.id());
        log.info("chat_out len={} action={} msg_id={}", text.length(), actionIssued, saved.id());
        pushStatus(true);
    }

    /** 计划不合法：把毛病告诉模型，让它重给一次（还是走 decide，所以它也可以改成单个动作）。 */
    private void reaskPlan(String replyId, String inReplyTo, StoredMessage src, int attempt, String prefix, List<String> errors) {
        String again = src.text() + "\n\n[系统] 你上次给的计划有问题：" + String.join("；", errors)
                + "。只能用工具列表里的命令和参数，最多 " + PlanValidator.MAX_STEPS + " 步。请重新给一个计划；一个动作就能做完就直接调那个工具。";
        BrainContext ctx = context();
        runBrain(() -> {
            try {
                ModelReply reply = brain.decide(again, ctx, chunk -> { });
                submit(() -> onDecision(replyId, inReplyTo, src, reply, attempt, prefix));
            } catch (ModelException ex) {
                submit(() -> onModelFailure(replyId, inReplyTo, ex));
            }
        });
    }

    private void onModelFailure(String replyId, String inReplyTo, ModelException ex) {
        modelOk = false;
        log.warn("model_fail kind={} msg={}", ex.kind(), ex.getMessage());
        notice(NOTICE_MODEL_DOWN, "warn");
        replyDone(replyId, inReplyTo, null);
        pushStatus(true);
    }

    private String issueCommand(CommandName name, JsonObject args, String sourceMessageId) {
        Envelope cmd = envelope(MessageType.COMMAND, Peer.MC, new Command(name, args == null ? new JsonObject() : args));
        commandNames.put(cmd.id(), name);
        if (sourceMessageId != null) {
            commandSource.put(cmd.id(), sourceMessageId);
        }
        if (name == CommandName.STOP) {
            stopCommands.put(cmd.id(), "model");
            slot.onStopIssued(now());
        } else {
            slot.start(cmd.id(), name, now(), sourceMessageId);
        }
        mc.send(cmd);
        memory.saveEvent("command", cmd.id(), sourceMessageId, obj("name", name.wire()));
        log.info("command name={} cmd_id={}", name.wire(), cmd.id());
        return cmd.id();
    }

    private void onStopButton() {
        log.info("stop_button");
        memory.saveEvent("stop_button", null, null, null);
        abortPlanOnStop(PlanRun.Outcome.ABORTED);
        if (mc == null) {
            notice("伙伴不在线，没什么可停的", "info");
            return;
        }
        Envelope cmd = envelope(MessageType.COMMAND, Peer.MC, Command.of(CommandName.STOP));
        commandNames.put(cmd.id(), CommandName.STOP);
        stopCommands.put(cmd.id(), "button");
        slot.onStopIssued(now());
        mc.send(cmd);
        memory.saveEvent("command", cmd.id(), null, obj("name", "stop"));
        log.info("command name=stop cmd_id={} source=button", cmd.id());
        pushStatus(true);
    }

    /** 结果到了，让 Brain 说一句话；模型挂了就用固定描述兜底。 */
    private void say(CommandName name, Result r) {
        say(name, r, null);
    }

    /** @param goal 多步计划全部做完时带上主人要的事，让它知道整件事成了（任务单 11） */
    private void say(CommandName name, Result r, String goal) {
        String source = commandSource.remove(r.commandId());
        String replyId = UUID.randomUUID().toString();
        BrainContext ctx = context();
        runBrain(() -> {
            String text;
            try {
                ModelReply reply = brain.sayAfterResult(name, r, goal, ctx, chunk -> submit(() -> replyChunk(replyId, source, chunk)));
                text = reply.text().strip();
                submit(() -> modelOk = true);
            } catch (ModelException ex) {
                text = Brain.describeResult(name, r);
                String fallback = text;
                submit(() -> {
                    modelOk = false;
                    log.warn("model_fail kind={} msg={} (say_after_result)", ex.kind(), ex.getMessage());
                    replyChunk(replyId, source, fallback);
                });
            }
            String finalText = text;
            submit(() -> {
                StoredMessage saved = memory.saveMessage("companion", finalText, source, null);
                replyDone(replyId, source, saved.id());
                log.info("chat_out len={} after_result={} msg_id={}", finalText.length(), name.wire(), saved.id());
                pushStatus(true);
            });
        });
    }

    // ---------------- /ws/keeper ----------------

    public void keeperConnected(Sender s) {
        if (keeper != null && keeper != s) {
            keeper.close(4409, "replaced");
        }
        keeper = s;
        s.send(envelope(MessageType.HELLO, Peer.KEEPER,
                new HelloOk(true, now(), ProtocolCodec.VERSION, keeperDesired, null, null, null, null)));
        log.info("keeper online desired={}", keeperDesired);
        pushStatus(true);
    }

    public void keeperDisconnected(Sender s) {
        if (keeper != s) {
            return;
        }
        keeper = null;
        keeperStatus = null;
        log.info("keeper offline");
        pushStatus(true);
    }

    public void keeperMessage(Envelope e) {
        if (e.type() == MessageType.KEEPER_STATUS) {
            KeeperStatus st = ProtocolCodec.bodyAs(e, KeeperStatus.class);
            boolean changed = keeperStatus == null || keeperStatus.running() != st.running()
                    || keeperStatus.gaveUp() != st.gaveUp();
            keeperStatus = st;
            pushStatus(changed);
        }
    }

    private void keeperControl(String action) {
        if ("auto_on".equals(action) || "auto_off".equals(action)) {
            setAutoPresence("auto_on".equals(action));
            return;
        }
        if (!"start".equals(action) && !"stop".equals(action)) {
            return;
        }
        keeperDesired = "start".equals(action) ? "running" : "stopped";
        memory.setSetting("keeper_desired", keeperDesired);
        log.info("keeper_control action={}", action);
        if (keeper == null) {
            notice("keeper 没连上，它上线后会按这个状态执行", "warn");
        } else {
            keeper.send(envelope(MessageType.KEEPER_CMD, Peer.KEEPER, new KeeperCmd(action)));
        }
        pushStatus(true);
    }

    // ---------------- 自动上下线（手册 7.2 可选项） ----------------

    private void setAutoPresence(boolean on) {
        if (on && !autoAvailable) {
            notice("config.yaml 没配 rcon，自动上下线用不了", "warn");
            pushStatus(true);
            return;
        }
        autoPresence = on;
        ownerOnline = null;
        rconFailures = 0;
        presence.reset();
        memory.setSetting("keeper_auto", on ? "on" : "off");
        log.info("auto_presence {}", on ? "on" : "off");
        pushStatus(true);
    }

    /** PresencePoller 在自己的线程上读这个，决定要不要去查。 */
    public boolean autoPresenceEnabled() {
        return autoPresence;
    }

    /** RCON `list` 查到的在线名单（hub 线程）。 */
    public void presenceObserved(List<String> online) {
        if (!autoPresence) {
            return;
        }
        boolean nowOnline = online.contains(cfg.ownerName);
        boolean changed = ownerOnline == null || ownerOnline != nowOnline;
        ownerOnline = nowOnline;
        if (rconFailures > 0) {
            log.info("presence rcon ok again after {} failure(s)", rconFailures);
            rconFailures = 0;
        }
        PresencePolicy.Action action = presence.observe(nowOnline, "running".equals(keeperDesired), now());
        if (changed || action != PresencePolicy.Action.NONE) {
            log.info("presence owner_online={} players={} action={}", nowOnline, online.size(), action);
        } else {
            log.debug("presence owner_online={} players={}", nowOnline, online.size());
        }
        switch (action) {
            case START -> {
                notice("看到你进服了，叫伙伴上线", "info");
                keeperControl("start");
            }
            case STOP -> {
                notice("你离开 " + cfg.autoPresence.offlineAfterMin() + " 分钟了，让伙伴下线", "info");
                keeperControl("stop");
            }
            default -> {
                if (changed) {
                    pushStatus(true);
                }
            }
        }
    }

    /** RCON 查询失败（hub 线程）：连续 5 次才提示一次，避免刷屏。 */
    public void presenceFailed(String why) {
        if (!autoPresence) {
            return;
        }
        rconFailures++;
        boolean changed = ownerOnline != null;
        ownerOnline = null;
        if (rconFailures == 1 || rconFailures % 5 == 0) {
            log.warn("presence rcon failed x{}: {}", rconFailures, why);
        }
        if (rconFailures == 5) {
            notice("查不到谁在线（RCON 连不上），自动上下线先停着", "warn");
        }
        if (changed) {
            pushStatus(true);
        }
    }

    // ---------------- 状态、提示、推送 ----------------

    public void tick() {
        long now = now();
        for (TaskSlot.Synth s : slot.check(now)) {
            handleSynth(s);
        }
        if (plan != null && plan.running() && now - plan.startedAt() > cfg.timeouts.planS() * 1000L) {
            log.info("plan_timeout plan_id={}", plan.planId());
            if (mc != null && plan.currentStep().isPresent()) {
                Envelope cmd = envelope(MessageType.COMMAND, Peer.MC, Command.of(CommandName.STOP));
                commandNames.put(cmd.id(), CommandName.STOP);
                stopCommands.put(cmd.id(), "button");
                slot.onStopIssued(now);
                mc.send(cmd);
                memory.saveEvent("command", cmd.id(), null, obj("name", "stop"));
            }
            abortPlanOnStop(PlanRun.Outcome.TIMEOUT);
        }
        if (now - lastStatusPush >= STATUS_INTERVAL_MS) {
            pushStatus(true);
        }
        if (awaitingStep != null && plan != null && plan.running() && now - awaitingSince > ASK_OWNER_TIMEOUT_MS) {
            log.info("ask_owner_timeout plan_id={}", plan.planId());
            awaitingStep = null;
            sayPlain("没等到你回话，这事先放着。");
            abortQuietly(PlanRun.Outcome.ABORTED);
        }
        idleTick(now);
    }

    // ---------------- 空闲循环（任务单 18） ----------------

    /** 每 500 ms 看一眼：开关开着、模型空着、没计划在跑、它在世界里、主人在服里、时间上到了，才让脑子想一想。 */
    private void idleTick(long now) {
        if (!idleEnabled || idleThinking || brainBusy > 0 || !idle.dueToThink(now)) {
            return;
        }
        if (!companionInWorld() || (plan != null && plan.running())) {
            return;
        }
        boolean ownerHere = (ownerOnline != null && ownerOnline)
                || (lastSnapshot != null && lastSnapshot.owner() != null && lastSnapshot.owner().visible());
        if (!ownerHere) {
            return;
        }
        idle.thought(now);
        idleThinking = true;
        String situation = idleSituation(now);
        BrainContext ctx = context();
        runBrain(() -> {
            try {
                Brain.IdleDecision d = brain.idleThink(situation, ctx);
                submit(() -> onIdleDecision(d));
            } catch (ModelException ex) {
                submit(() -> {
                    idleThinking = false;
                    log.warn("idle_think failed: {}", ex.getMessage());
                });
            }
        });
    }

    /** 给"想一想"的一段话：时间段、天气、生命饥饿、主人距离、最近被拒的建议（阶段和地标已经在提示里）。 */
    private String idleSituation(long now) {
        Snapshot s = lastSnapshot;
        StringBuilder sb = new StringBuilder();
        sb.append("时间：").append(timeOfDayZh(s == null ? null : s.timeOfDay())).append("；");
        if (s != null && s.isRaining() != null) {
            sb.append("天气：").append(s.isRaining() ? "下雨" : "晴").append("；");
        }
        if (s != null && s.health() != null) {
            sb.append("你的生命 ").append(Math.round(s.health())).append("/20");
            if (s.food() != null) {
                sb.append("，饥饿 ").append(s.food()).append("/20");
            }
            sb.append("；");
        }
        if (s != null && s.owner() != null && s.owner().visible() && s.owner().distance() != null) {
            sb.append("主人在 ").append(Math.round(s.owner().distance())).append(" 格外；");
        } else {
            sb.append("主人在服里但你看不见他；");
        }
        List<String> rejected = idle.recentlyRejected(now);
        if (!rejected.isEmpty()) {
            sb.append("最近被拒过的建议：").append(String.join("、", rejected)).append("；");
        }
        return sb.toString();
    }

    /** 世界时间 0–24000：白天 / 傍晚 / 夜里 / 快天亮。 */
    static String timeOfDayZh(Integer timeOfDay) {
        if (timeOfDay == null) {
            return "不知道几点";
        }
        int t = Math.floorMod(timeOfDay, 24000);
        if (t < 12000) {
            return t < 1000 ? "清晨" : t < 6000 ? "上午" : "下午";
        }
        if (t < 13000) {
            return "傍晚";
        }
        return t < 23000 ? "夜里" : "快天亮";
    }

    private void onIdleDecision(Brain.IdleDecision d) {
        idleThinking = false;
        long now = now();
        switch (d.action()) {
            case Brain.IdleDecision.REMIND -> {
                if (!idle.allowRemind(d.kind(), now)) {
                    log.info("idle_tick action=remind kind={} suppressed=cooldown", d.kind());
                    return;
                }
                sayProactive(d.say());
                idle.reminded(now);
                memory.saveEvent("idle_remind", null, null, obj("kind", d.kind()));
                log.info("idle_tick action=remind kind={}", d.kind());
            }
            case Brain.IdleDecision.PROPOSE -> {
                if (!idle.allowPropose(d.goal(), now)) {
                    log.info("idle_tick action=propose suppressed=rejected_recently goal_hash={}", IdlePolicy.goalKey(d.goal()).hashCode());
                    return;
                }
                PlanValidator.Validated v = PlanValidator.validate(makePlanArgs(d.goal(), d.steps()), knowledge.blockRules(), knowledge.mobRules());
                if (!v.ok()) {
                    log.info("idle_invalid_plan errors={}", v.errors());
                    return;
                }
                StoredMessage m = sayProactive(d.say());
                idle.proposed(d.goal(), d.steps(), m.id(), now);
                memory.saveEvent("idle_propose", null, m.id(), obj("goal", d.goal()));
                log.info("idle_tick action=propose goal_hash={} steps={} msg_id={}", IdlePolicy.goalKey(d.goal()).hashCode(),
                        v.steps().size(), m.id());
            }
            default -> log.info("idle_tick action=quiet");
        }
    }

    private static JsonObject makePlanArgs(String goal, com.google.gson.JsonArray steps) {
        JsonObject o = new JsonObject();
        o.addProperty("goal", goal == null ? "" : goal);
        o.add("steps", steps == null ? new com.google.gson.JsonArray() : steps);
        return o;
    }

    /** 它主动说的一句（source=idle）：网页另画样式，messages 表 source 列记 idle。 */
    private StoredMessage sayProactive(String text) {
        String replyId = UUID.randomUUID().toString();
        broadcast(envelope(MessageType.REPLY, Peer.UI, new Reply(replyId, null, text, false, null, "idle")));
        StoredMessage saved = memory.saveMessage("companion", text, null, null, "idle");
        broadcast(envelope(MessageType.REPLY, Peer.UI, new Reply(replyId, null, "", true, saved.id(), "idle")));
        log.info("chat_out len={} idle=true msg_id={}", text.length(), saved.id());
        return saved;
    }

    private void setIdleEnabled(boolean on) {
        idleEnabled = on;
        memory.setSetting("idle_enabled", on ? "on" : "off");
        log.info("idle_enabled {}", on ? "on" : "off");
        pushStatus(true);
    }

    public boolean companionInWorld() {
        return mcOnline && lastSnapshot != null && lastSnapshot.inWorld();
    }

    public boolean executorOnline() {
        return mcOnline;
    }

    public Optional<TaskSlot.Task> currentTask() {
        return slot.current();
    }

    Status status() {
        Status.CompanionState companion;
        if (!mcOnline) {
            companion = Status.CompanionState.offline();
        } else if (lastSnapshot == null) {
            companion = new Status.CompanionState(true, null, null, null, null, null, null);
        } else {
            String inv = lastSnapshot.inWorld() ? SnapshotSummary.inventory(lastSnapshot.inventory(), names) : null;
            companion = new Status.CompanionState(true, lastSnapshot.inWorld(), lastSnapshot.health(),
                    lastSnapshot.food(), lastSnapshot.dim(), lastSnapshot.pos(), inv);
        }
        TaskInfo task = slot.current()
                .map(t -> new TaskInfo(t.commandId(), t.name(), t.state().name().toLowerCase()))
                .orElse(null);
        Boolean auto = autoAvailable ? autoPresence : null;
        Status.KeeperState ks = keeper == null ? Status.KeeperState.disconnected(auto, ownerOnline)
                : new Status.KeeperState(true, keeperStatus == null ? null : keeperStatus.running(), keeperDesired,
                        keeperStatus == null ? null : keeperStatus.gaveUp(), auto, ownerOnline);
        return new Status(companion, task, ks, new Status.ModelState(modelOk), idleEnabled, combatMode);
    }

    private void pushStatus(boolean force) {
        long now = now();
        if (!force && now - lastStatusPush < STATUS_INTERVAL_MS) {
            return;
        }
        lastStatusPush = now;
        broadcast(envelope(MessageType.STATUS, Peer.UI, status()));
    }

    private void pushPreferences(Sender only) {
        List<Preference> pending = memory.preferences("pending");
        List<Preferences.Item> items = pending.stream().map(p -> new Preferences.Item(p.id(), p.text(), p.ts())).toList();
        Envelope e = envelope(MessageType.PREFERENCES, Peer.UI, new Preferences(items));
        if (only != null) {
            only.send(e);
        } else {
            broadcast(e);
        }
    }

    private void notice(String text, String level) {
        broadcast(envelope(MessageType.NOTICE, Peer.UI, new Notice(text, level)));
    }

    private void replyChunk(String replyId, String inReplyTo, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        broadcast(envelope(MessageType.REPLY, Peer.UI, new Reply(replyId, inReplyTo, chunk, false, null)));
    }

    private void replyDone(String replyId, String inReplyTo, String messageId) {
        broadcast(envelope(MessageType.REPLY, Peer.UI, new Reply(replyId, inReplyTo, "", true, messageId)));
    }

    private void broadcast(Envelope e) {
        for (Sender s : List.copyOf(uis)) {
            try {
                s.send(e);
            } catch (RuntimeException ex) {
                log.debug("ui send failed, dropping: {}", ex.toString());
                uis.remove(s);
            }
        }
    }

    private JsonArray historyJson() {
        JsonArray a = new JsonArray();
        for (StoredMessage m : memory.recentMessages(cfg.historyN)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id());
            o.addProperty("ts", m.ts());
            o.addProperty("role", m.role());
            o.addProperty("text", m.text());
            a.add(o);
        }
        return a;
    }

    private BrainContext context() {
        List<String> prefs = memory.preferences("active").stream().map(Preference::text).toList();
        Progression prog = knowledge.progression();
        String progressLine = prog.isEmpty() ? "" : prog.describe(assessProgress(), names);
        Pos here = lastSnapshot != null && lastSnapshot.inWorld() ? lastSnapshot.pos() : null;
        String dim = lastSnapshot != null ? lastSnapshot.dim() : null;
        List<String> landmarks = Landmarks.nearest(memory.worldFacts(), here, dim, 10);
        return new BrainContext(lastSnapshot, mcOnline, prefs, memory.recentEpisodes(3),
                historyWindow.select(memory.recentMessages(cfg.historyN * 2)), plansForPrompt(), memory.places(),
                progressLine, landmarks);
    }

    /** 任务单 17："今天做过的事"：最近 5 个计划加上今天结束的全部，去重按结束时间排。 */
    private List<dev.blockmate.server.memory.StoredPlan> plansForPrompt() {
        long startOfDay = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        java.util.LinkedHashMap<String, dev.blockmate.server.memory.StoredPlan> m = new java.util.LinkedHashMap<>();
        for (dev.blockmate.server.memory.StoredPlan sp : memory.recentPlans(5)) {
            m.put(sp.id(), sp);
        }
        for (dev.blockmate.server.memory.StoredPlan sp : memory.plansSince(startOfDay)) {
            m.putIfAbsent(sp.id(), sp);
        }
        List<dev.blockmate.server.memory.StoredPlan> out = new ArrayList<>(m.values());
        out.sort(java.util.Comparator.comparingLong(dev.blockmate.server.memory.StoredPlan::endedTs));
        return out;
    }

    // ---------------- 日记与备份 ----------------

    public void writeDailyEpisode() {
        writeEpisodeFor(LocalDate.now());
    }

    /**
     * 启动后补写昨天的日记（2026-10-23）：23:55 那次可能超时失败、或后台当时没开、或当天的日记是伙伴中途掉线时写的半截
     * （created_ts 早于当天结束）、或之后删过消息（设置表里有 episode_stale:日期）。满足任一条就重写一遍。
     */
    public void catchUpEpisodes() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        long dayEnd = yesterday.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String day = yesterday.toString();
        Optional<Episode> existing = memory.episodeForDay(day);
        boolean stale = memory.setting("episode_stale:" + day).isPresent();
        if (existing.isEmpty() || existing.get().createdTs() < dayEnd || stale) {
            log.info("episode catch-up day={} reason={}", day,
                    existing.isEmpty() ? "missing" : stale ? "stale" : "written_before_day_end");
            writeEpisodeFor(yesterday);
        }
    }

    private void maybeWriteEpisodeOnOffline() {
        LocalDate today = LocalDate.now();
        if (memory.episodeForDay(today.toString()).isEmpty()) {
            writeEpisodeFor(today);
        }
    }

    private void writeEpisodeFor(LocalDate day) {
        long from = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<StoredEvent> events = memory.eventsBetween(from, to, Set.of("progress", "model_call", "ack"));
        List<StoredMessage> msgs = memory.messagesBetween(from, to);
        if (events.isEmpty() && msgs.isEmpty()) {
            log.info("episode skip day={} (nothing happened)", day);
            return;
        }
        String dayStr = day.toString();
        runBrain(() -> {
            try {
                String summary = brain.writeEpisode(dayStr, events, msgs);
                if (!summary.isBlank()) {
                    submit(() -> memory.saveEpisode(dayStr, summary, from, to));
                }
            } catch (ModelException e) {
                log.warn("episode_fail day={} kind={} msg={}", dayStr, e.kind(), e.getMessage());
            }
        });
    }

    public void backup(Path dir, int keep) {
        try {
            memory.backup(dir, keep);
        } catch (RuntimeException e) {
            log.warn("backup_fail: {}", e.getMessage());
        }
    }

    // ---------------- 小工具 ----------------

    private static Envelope envelope(MessageType type, Peer to, Object body) {
        return ProtocolCodec.envelope(type, Peer.HUB, to, body);
    }

    private static JsonObject obj(String key, Object value) {
        JsonObject o = new JsonObject();
        if (value instanceof Number n) {
            o.addProperty(key, n);
        } else if (value != null) {
            o.addProperty(key, String.valueOf(value));
        }
        return o;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
