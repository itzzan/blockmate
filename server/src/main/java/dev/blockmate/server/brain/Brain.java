package dev.blockmate.server.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blockmate.server.gateway.ToolCall;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.DeliveryOutcome;
import dev.blockmate.protocol.Reason;
import dev.blockmate.protocol.Result;
import dev.blockmate.protocol.ResultStatus;
import dev.blockmate.server.gateway.ChatMessage;
import dev.blockmate.server.gateway.ChatRequest;
import dev.blockmate.server.gateway.ModelClient;
import dev.blockmate.server.gateway.ModelException;
import dev.blockmate.server.gateway.ModelReply;
import dev.blockmate.server.memory.StoredEvent;
import dev.blockmate.server.memory.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Brain（手册 5.5 第 3 条）：决定说什么、做什么；结果到了说一句话；每天写日记。 */
public final class Brain {

    private static final Logger log = LoggerFactory.getLogger(Brain.class);
    /** 写日记这一次调用的超时（2026-10-23：23:55 的日记用 30 秒的聊天超时等不到首字）。 */
    static final Duration EPISODE_FIRST_TOKEN = Duration.ofSeconds(120);
    static final Duration EPISODE_TOTAL = Duration.ofSeconds(240);

    private final ModelClient client;
    private final String model;
    private final Persona persona;
    private final ItemNames names;
    private final String ownerName;
    private final String companionName;

    private final Knowledge knowledge;

    public Brain(ModelClient client, String model, Persona persona, ItemNames names, String ownerName,
                 String companionName) {
        this(client, model, persona, Knowledge.empty(), names, ownerName, companionName);
    }

    public Brain(ModelClient client, String model, Persona persona, Knowledge knowledge, ItemNames names,
                 String ownerName, String companionName) {
        this.client = client;
        this.model = model;
        this.persona = persona;
        this.knowledge = knowledge == null ? Knowledge.empty() : knowledge;
        this.names = names;
        this.ownerName = ownerName;
        this.companionName = companionName;
    }

    /** 决策请求：带全部工具。 */
    public ModelReply decide(String userText, BrainContext ctx, Consumer<String> onChunk) throws ModelException {
        List<ChatMessage> messages = PromptBuilder.build(persona.text(), knowledge.planExamples(), ctx, names, userText, true);
        ModelReply reply = client.chat(new ChatRequest(model, messages, Tools.all()), onChunk);
        log.info("decide text_len={} tools={}", reply.text().length(), reply.toolCalls().size());
        return reply;
    }

    /** 结果到达后的"说一句话"请求：不带动作工具（手册 5.5 第 3 条）。 */
    public ModelReply sayAfterResult(CommandName name, Result result, BrainContext ctx, Consumer<String> onChunk)
            throws ModelException {
        return sayAfterResult(name, result, null, ctx, onChunk);
    }

    /** @param goal 不为空时表示多步计划全部做完，最后一步是 name（任务单 11） */
    public ModelReply sayAfterResult(CommandName name, Result result, String goal, BrainContext ctx, Consumer<String> onChunk)
            throws ModelException {
        String head = goal == null || goal.isBlank()
                ? "[系统] 你刚才做的「" + SnapshotSummary.taskName(name) + "」结束了："
                : "[系统] 主人要的「" + goal + "」你全部做完了，最后一步「" + SnapshotSummary.taskName(name) + "」：";
        String prompt = head + describeResult(name, result)
                + "。用一两句话告诉主人，像平时说话那样，不要复述这段系统提示。"
                + "如果上面写的是失败，就如实说没做成和原因，绝不能说成已经做好了。";
        List<ChatMessage> messages = PromptBuilder.build(persona.text(), knowledge.planExamples(), ctx, names, prompt, false);
        ModelReply reply = client.chat(new ChatRequest(model, messages, Tools.chatOnly()), onChunk);
        log.info("say_after_result name={} text_len={}", name.wire(), reply.text().length());
        return reply;
    }

    /** 计划里一步失败后脑子的决定（任务单 11）：四选一，先说一句；replan 时带新的剩余步骤（hub 再校验）。 */
    public record PlanDecision(String action, String say, JsonArray newSteps) {
        public static final String RETRY = "retry";
        public static final String SKIP = "skip";
        public static final String REPLAN = "replan";
        public static final String ABORT = "abort";
    }

    /**
     * @param doneSummary 已完成的步，如"已做完：挖东西、过来"；没有就空串
     */
    public PlanDecision planFailure(String goal, int stepIndex, int stepCount, CommandName step, Result result,
                                    String doneSummary, int retriesLeft, BrainContext ctx) throws ModelException {
        String prompt = "[系统] 你在做「" + goal + "」这件事，一共 " + stepCount + " 步，第 " + (stepIndex + 1) + " 步「"
                + SnapshotSummary.taskName(step) + "」没做成：" + describeResult(step, result) + "。"
                + (doneSummary.isBlank() ? "" : doneSummary + "。")
                + "剩余重试额度 " + retriesLeft + " 次。请调用 plan_decision 决定接下来怎么办（retry / skip / replan / abort），"
                + "并在 say 里先对主人说一句你要怎么做，像平时说话那样。";
        List<ChatMessage> messages = PromptBuilder.build(persona.text(), knowledge.planExamples(), ctx, names, prompt, false);
        ModelReply reply = client.chat(new ChatRequest(model, messages, Tools.planDecision()), chunk -> { });
        PlanDecision d = parseDecision(reply);
        log.info("plan_failure step={}/{} command={} decision={}", stepIndex + 1, stepCount, step.wire(), d.action());
        return d;
    }

    static PlanDecision parseDecision(ModelReply reply) {
        for (ToolCall tc : reply.toolCalls()) {
            if (Tools.PLAN_DECISION.equals(tc.name()) && tc.arguments() != null) {
                JsonObject a = tc.arguments();
                String action = a.has("action") && !a.get("action").isJsonNull() ? a.get("action").getAsString().strip().toLowerCase() : "";
                if (!List.of(PlanDecision.RETRY, PlanDecision.SKIP, PlanDecision.REPLAN, PlanDecision.ABORT).contains(action)) {
                    action = PlanDecision.ABORT;
                }
                String say = a.has("say") && !a.get("say").isJsonNull() ? a.get("say").getAsString().strip() : "";
                JsonArray steps = a.has("new_steps") && a.get("new_steps").isJsonArray() ? a.getAsJsonArray("new_steps") : null;
                return new PlanDecision(action, say, steps);
            }
        }
        // 没调工具：按文字里的词兜底，默认放弃
        String t = reply.text() == null ? "" : reply.text();
        String action = t.contains("重试") ? PlanDecision.RETRY : t.contains("跳过") ? PlanDecision.SKIP : PlanDecision.ABORT;
        return new PlanDecision(action, t.strip(), null);
    }

    /** 任务单 18：空闲循环"想一想"的结果。action ∈ propose / remind / quiet。 */
    public record IdleDecision(String action, String say, String goal, JsonArray steps, String kind) {
        public static final String PROPOSE = "propose";
        public static final String REMIND = "remind";
        public static final String QUIET = "quiet";

        public static IdleDecision quiet() {
            return new IdleDecision(QUIET, "", null, null, null);
        }
    }

    /**
     * 任务单 18：没人说话时想一想要不要主动说点什么。situation 是 hub 拼好的一段：时间段、天气、生命饥饿、主人距离、最近被拒的建议。
     * 只给 propose / remind / stay_quiet 三个工具；模型不调工具就当 stay_quiet。不流式。
     */
    public IdleDecision idleThink(String situation, BrainContext ctx) throws ModelException {
        String prompt = "[系统] 现在没人跟你说话，主人在服里。" + situation
                + "\n想一想要不要主动说点什么。大多数时候选 stay_quiet；只有当前阶段明显该去做一件事、或者天黑 / 饿了 / 血少 / 有危险时才开口。"
                + "要提议就调 propose（say 像平时说话问一句要不要，goal 一句话目标，steps 和 make_plan 一样只用工具列表里的命令）；"
                + "要提醒就调 remind。最近被拒过的建议别再提。";
        List<ChatMessage> messages = PromptBuilder.build(persona.text(), knowledge.planExamples(), ctx, names, prompt, false);
        ModelReply reply = client.chat(new ChatRequest(model, messages, Tools.idle()), chunk -> { });
        IdleDecision d = parseIdle(reply);
        log.info("idle_think action={} say_len={}", d.action(), d.say().length());
        return d;
    }

    static IdleDecision parseIdle(ModelReply reply) {
        for (ToolCall tc : reply.toolCalls()) {
            JsonObject a = tc.arguments() == null ? new JsonObject() : tc.arguments();
            String say = a.has("say") && !a.get("say").isJsonNull() ? a.get("say").getAsString().strip() : "";
            if (Tools.PROPOSE.equals(tc.name())) {
                String goal = a.has("goal") && !a.get("goal").isJsonNull() ? a.get("goal").getAsString().strip() : "";
                JsonArray steps = a.has("steps") && a.get("steps").isJsonArray() ? a.getAsJsonArray("steps") : null;
                if (say.isEmpty() || goal.isEmpty() || steps == null || steps.isEmpty()) {
                    return IdleDecision.quiet();
                }
                return new IdleDecision(IdleDecision.PROPOSE, say, goal, steps, null);
            }
            if (Tools.REMIND.equals(tc.name())) {
                String kind = a.has("kind") && !a.get("kind").isJsonNull() ? a.get("kind").getAsString().strip().toLowerCase() : "";
                if (say.isEmpty() || !List.of("night", "hungry", "low_health", "danger").contains(kind)) {
                    return IdleDecision.quiet();
                }
                return new IdleDecision(IdleDecision.REMIND, say, null, null, kind);
            }
            if (Tools.STAY_QUIET.equals(tc.name())) {
                return IdleDecision.quiet();
            }
        }
        return IdleDecision.quiet();
    }

    /** 把一天的事概括成一段日记（手册 5.5 第 5 条）。不带工具。 */
    public String writeEpisode(String day, List<StoredEvent> events, List<StoredMessage> messages)
            throws ModelException {
        StringBuilder sb = new StringBuilder();
        sb.append("[系统] 今天是 ").append(day).append("。下面是今天发生的事，请你以 ").append(companionName)
                .append(" 的口吻写一段 100 字以内的日记，记下和 ").append(ownerName)
                .append(" 一起做了什么、聊了什么重要的，不要列清单，不要加标题。\n\n");
        int n = 0;
        for (StoredMessage m : messages) {
            if (n++ > 60) {
                sb.append("……（更多对话略）\n");
                break;
            }
            String who = switch (m.role()) {
                case "owner", "owner_ingame" -> ownerName;
                case "companion" -> companionName;
                default -> "系统";
            };
            sb.append(who).append("：").append(m.text().strip()).append('\n');
        }
        sb.append('\n');
        String digest = EpisodeDigest.describe(events);
        if (!digest.isBlank()) {
            sb.append("游戏里发生的事（按动作统计）：\n").append(digest).append('\n');
        }
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(persona.text()));
        prompt.add(ChatMessage.user(sb.toString()));
        // 日记的提示比平时长得多（一天的对话 + 事件），冷缓存下首字要等很久，用单独的长超时
        ModelReply reply = client.chat(new ChatRequest(model, prompt, null), chunk -> { }, EPISODE_FIRST_TOKEN, EPISODE_TOTAL);
        log.info("episode day={} text_len={}", day, reply.text().length());
        return reply.text().strip();
    }

    /** 结果的中文描述，给模型看，也作为模型挂掉时的兜底回复。 */
    public static String describeResult(CommandName name, Result r) {
        if (r.status() == ResultStatus.OK) {
            if (name == CommandName.DELIVER && r.detail() != null) {
                Result.DeliverDetail d = r.detailAs(Result.DeliverDetail.class);
                if (d != null && d.delivery() == DeliveryOutcome.THROWN_UNCONFIRMED) {
                    return "东西丢在主人脚边了，但没看到主人捡起来，不知道收没收到";
                }
            }
            if (name == CommandName.PICK_UP && r.detail() != null) {
                Result.PickUpDetail d = r.detailAs(Result.PickUpDetail.class);
                if (d != null) {
                    return "成功，捡到了 " + d.picked() + " 个，现在在你自己的背包里";
                }
            }
            if (name == CommandName.DELIVER && r.detail() != null) {
                Result.DeliverDetail d = r.detailAs(Result.DeliverDetail.class);
                if (d != null && d.delivery() == DeliveryOutcome.CONFIRMED) {
                    return "成功，丢了 " + d.count() + " 个，看到主人捡起来了";
                }
            }
            if (name == CommandName.STOP && r.detail() != null) {
                Result.StopDetail d = r.detailAs(Result.StopDetail.class);
                if (d != null && !d.stationary()) {
                    return "想停但身体还在滑";
                }
            }
            // 任务单 13 / 14 的动作：把数量说出来
            if (r.detail() != null) {
                switch (name) {
                    case MINE -> {
                        Result.MineDetail d = r.detailAs(Result.MineDetail.class);
                        if (d != null) {
                            return "成功，挖到了 " + d.mined() + " 个，在你自己的背包里";
                        }
                    }
                    case BUILD_PORTAL -> {
                        Result.BuildPortalDetail d = r.detailAs(Result.BuildPortalDetail.class);
                        if (d != null) {
                            if (r.status() == dev.blockmate.protocol.ResultStatus.OK) {
                                return "成功，门框搭好了（放了 " + d.placed() + " 块），等主人说点";
                            }
                            String note = d.note() == null ? "" : d.note();
                            return switch (note) {
                                case "no_flat_ground" -> "失败，原因：这儿不平，换个地方再说";
                                case "frame_incomplete" -> "失败，原因：门框没搭全，放了 " + d.placed() + " 块";
                                case "place_failed" -> "失败，原因：有一块放不上去，放了 " + d.placed() + " 块";
                                default -> "失败，原因：" + reasonZh(r.reason());
                            };
                        }
                    }
                    case LIGHT_PORTAL -> {
                        if (r.status() == dev.blockmate.protocol.ResultStatus.OK) {
                            return "成功，门点着了";
                        }
                    }
                    case ENTER_PORTAL -> {
                        Result.EnterPortalDetail d = r.detailAs(Result.EnterPortalDetail.class);
                        if (d != null && r.status() == dev.blockmate.protocol.ResultStatus.OK) {
                            return "成功，到" + SnapshotSummary.dimName(d.dim()) + "了";
                        }
                    }
                    case EXPLORE -> {
                        Result.ExploreDetail d = r.detailAs(Result.ExploreDetail.class);
                        if (d != null && r.status() == dev.blockmate.protocol.ResultStatus.OK) {
                            return "成功，找到" + Landmarks.kindName(d.found()) + "了";
                        }
                    }
                    case FIGHT -> {
                        Result.FightDetail d = r.detailAs(Result.FightDetail.class);
                        if (d != null) {
                            return "成功，打死了 " + d.killed() + " 只";
                        }
                    }
                    case COLLECT -> {
                        Result.CollectDetail d = r.detailAs(Result.CollectDetail.class);
                        if (d != null) {
                            return "成功，捡了 " + d.picked() + " 个";
                        }
                    }
                    case CRAFT -> {
                        Result.CraftDetail d = r.detailAs(Result.CraftDetail.class);
                        if (d != null) {
                            return "成功，做出来 " + d.crafted() + " 个，在你自己的背包里";
                        }
                    }
                    case LEAD, GOTO, GO_HOME -> {
                        return "成功，到了";
                    }
                    case PLACE -> {
                        return "成功，放好了";
                    }
                    case PICKUP_BLOCK -> {
                        return "成功，收回来了";
                    }
                    case REMEMBER_PLACE -> {
                        return "成功，记住这个地点了";
                    }
                    case USE_FURNACE -> {
                        Result.UseFurnaceDetail d = r.detailAs(Result.UseFurnaceDetail.class);
                        if (d != null) {
                            return "成功，烧好了 " + d.smelted() + " 个，在你自己的背包里";
                        }
                    }
                    case EAT -> {
                        Result.EatDetail d = r.detailAs(Result.EatDetail.class);
                        if (d != null && d.item() == null) {
                            return "饱着呢（饥饿值 " + d.foodAfter() + "/20），吃不下，没吃";
                        }
                        if (d != null) {
                            return "成功，吃了 " + d.item() + "，饥饿值现在 " + d.foodAfter() + "/20";
                        }
                    }
                    case SLEEP -> {
                        return "成功，躺到床上了，天亮会自己起来";
                    }
                    default -> {
                    }
                }
            }
            return "成功";
        }
        if (r.status() == ResultStatus.CANCELLED) {
            return "被新的指令打断，没做完";
        }
        String base = "失败，原因：" + reasonZh(r.reason());
        if (r.detail() != null) {
            if (name == CommandName.CRAFT || name == CommandName.PLACE) {
                Result.CraftDetail d = r.detailAs(Result.CraftDetail.class);
                if (d != null) {
                    StringBuilder sb = new StringBuilder(base);
                    if (d.missing() != null && !d.missing().isEmpty()) {
                        sb.append("（缺");
                        d.missing().forEach((k, v) -> sb.append(" ").append(k).append("×").append(v));
                        sb.append("）");
                    }
                    if (d.crafted() > 0) {
                        sb.append("，之前已经做出来 ").append(d.crafted()).append(" 个");
                    }
                    return sb.toString();
                }
            }
            if (name == CommandName.MINE) {
                Result.MineDetail d = r.detailAs(Result.MineDetail.class);
                if (d != null && d.mined() > 0) {
                    return base + "，之前已经挖到 " + d.mined() + " 个";
                }
            }
            if (name == CommandName.COLLECT) {
                Result.CollectDetail d = r.detailAs(Result.CollectDetail.class);
                if (d != null && d.picked() > 0) {
                    return base + "，之前已经捡了 " + d.picked() + " 个";
                }
            }
            if (name == CommandName.USE_FURNACE) {
                Result.UseFurnaceDetail d = r.detailAs(Result.UseFurnaceDetail.class);
                if (d != null && d.smelted() > 0) {
                    return base + "，之前已经烧好 " + d.smelted() + " 个";
                }
            }
        }
        return base;
    }

    public static String reasonZh(Reason r) {
        if (r == null) {
            return "不清楚";
        }
        return switch (r) {
            case TARGET_LOST -> "找不到主人了";
            case TARGET_OTHER_DIM -> "主人去了别的世界";
            case TARGET_TOO_FAR -> "主人太远了";
            case NOT_PICKED -> "走到了但没捡到";
            case PATH_FAILED -> "找不到路过去";
            case STUCK -> "卡住了，动不了";
            case ITEM_NOT_FOUND -> "附近没有那个东西";
            case INVENTORY_FULL -> "背包满了";
            case TIMEOUT -> "做了太久没做完";
            case DIED -> "死了";
            case DISCONNECTED -> "掉线了";
            case EXECUTOR_UNRESPONSIVE -> "身体没反应";
            case REJECTED -> "现在做不了这个";
            // 任务单 9 加的
            case NOT_ALLOWED -> "那个方块不能碰";
            case NO_MATERIALS -> "材料不够";
            case NO_RECIPE -> "没有这个配方";
            case NO_BED -> "没有能睡的床";
            case NOT_NIGHT -> "还没到晚上，睡不了";
            case NO_PLACE_KNOWN -> "没记过那个地方";
            case PLAN_ABORTED -> "整个计划被停掉了";
        };
    }
}
