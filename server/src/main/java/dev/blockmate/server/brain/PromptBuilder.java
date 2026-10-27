package dev.blockmate.server.brain;

import dev.blockmate.server.gateway.ChatMessage;
import dev.blockmate.server.memory.Episode;
import dev.blockmate.server.memory.StoredMessage;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.Instant;
import dev.blockmate.server.memory.StoredPlace;
import dev.blockmate.server.memory.StoredPlan;

import java.util.ArrayList;
import java.util.List;

/** 组装提示词（细则第 9 节的顺序）。 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /** 动作规则，放在人设后面（2026-10-22：模型常用文字答应"等着/停了"却不调工具）。 */
    static final String ACTION_RULE = "动作规则：主人让你停、等、过来、跟着、捡、给的时候，必须调用对应的工具，哪怕你正在做别的；"
            + "只用文字答应而不调工具，等于没做。例子：主人说「等在这」→ 调用 wait_here；主人说「停」→ 调用 stop；"
            + "主人说「过来」→ 调用 come_here。地上有什么以状态句里的「地上 8 格内有」为准：主人说「把木头捡起来」就调用 pick_up，"
            + "不要凭背包里已经有多少来推断地上没有；状态句说地上没东西而主人坚持要捡，也调用 pick_up 去试。闲聊不用工具。\n"
            + "说话规则：别重复你上一轮说过的话或口头禅，同一件事换个说法，或者说点新的。\n"
            + "捡东西规则：主人让你捡、拿、收地上的东西时直接动手——全部捡用 collect，指定一种用 pick_up——不要反问要不要捡。\n"
            + "计划规则：主人要的事一个动作做不完时用 make_plan 给出几步（最多 8 步），同时用一句话说你打算怎么做，"
            + "不要念步骤清单；一个动作就能做完的事直接用那个工具，不要 make_plan。计划开始后每做完一步不用说话，全部做完再说一句。";

    /**
     * system 部分只放一次对话里基本不变的东西（人设、偏好、日记），不放每轮都变的状态句，
     * 这样 Ollama 的前缀缓存能一直命中（2026-10-22：状态句在 system 里时每轮重算 1000 多 token，首字 9 秒）。
     */
    public static String systemPrompt(String persona, BrainContext ctx) {
        return systemPrompt(persona, "", ctx);
    }

    /** planExamples 是 knowledge/plan_examples.md 的全文（任务单 11），放在动作规则后面，一次对话里不变。 */
    public static String systemPrompt(String persona, String planExamples, BrainContext ctx) {
        StringBuilder sb = new StringBuilder(persona.strip()).append("\n\n");
        sb.append(ACTION_RULE).append("\n\n");
        if (planExamples != null && !planExamples.isBlank()) {
            sb.append(planExamples.strip()).append("\n\n");
        }
        if (ctx.activePreferences() != null && !ctx.activePreferences().isEmpty()) {
            sb.append("已确认的偏好和约定：\n");
            for (String p : ctx.activePreferences()) {
                sb.append("- ").append(p).append('\n');
            }
            sb.append('\n');
        }
        if (ctx.episodes() != null && !ctx.episodes().isEmpty()) {
            sb.append("最近几天的日记（你自己写的）：\n");
            for (Episode e : ctx.episodes()) {
                sb.append("- ").append(e.day()).append("：").append(e.summary().strip()).append('\n');
            }
            sb.append('\n');
        }
        if (ctx.recentPlans() != null && !ctx.recentPlans().isEmpty()) {
            sb.append("最近做过的任务（结局）：\n");
            for (StoredPlan p : ctx.recentPlans()) {
                sb.append("- ").append(DAY.format(Instant.ofEpochMilli(p.endedTs()))).append(' ')
                        .append(p.goal().strip()).append('：').append(outcomeZh(p.outcome())).append('\n');
            }
            sb.append('\n');
        }
        if (ctx.places() != null && !ctx.places().isEmpty()) {
            sb.append("记过的地点（说\"回家\"或\"去 xx\"就能去）：");
            List<String> names = new ArrayList<>();
            for (StoredPlace pl : ctx.places()) {
                names.add(pl.name() + "（" + Math.round(pl.x()) + ", " + Math.round(pl.y()) + ", " + Math.round(pl.z()) + "）");
            }
            sb.append(String.join("、", names)).append("\n\n");
        } else {
            sb.append("还没记过任何地点：主人说\"记住这里是家\"你就记下 home；没记过家时主人让你回家，要先问家在哪。\n\n");
        }
        return sb.toString().strip();
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日").withZone(ZoneId.systemDefault());

    static String outcomeZh(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "done" -> "做成了";
            case "failed" -> "没做成";
            case "aborted" -> "中途停了";
            case "timeout" -> "太久没做完";
            default -> outcome == null ? "不清楚" : outcome;
        };
    }

    /** 每轮都变的状态句，放在最后一条消息里。 */
    public static String stateLine(BrainContext ctx, ItemNames names) {
        return "现在的状态：" + SnapshotSummary.describe(ctx.snapshot(), ctx.executorOnline(), names);
    }

    /** system + 最近对话 + 这次的输入（来自主人的话）。 */
    public static List<ChatMessage> build(String persona, BrainContext ctx, ItemNames names, String userText) {
        return build(persona, ctx, names, userText, true);
    }

    /**
     * system + 最近对话 + 最后一条 user：状态句 + 这次的输入。
     * fromOwner=true 时输入前面加"主人说："，让模型分清状态和主人的话；false 用于内部提示（动作结束后让它说一句）。
     */
    public static List<ChatMessage> build(String persona, BrainContext ctx, ItemNames names, String userText,
                                          boolean fromOwner) {
        return build(persona, "", ctx, names, userText, fromOwner);
    }

    public static List<ChatMessage> build(String persona, String planExamples, BrainContext ctx, ItemNames names,
                                          String userText, boolean fromOwner) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(systemPrompt(persona, planExamples, ctx)));
        if (ctx.history() != null) {
            for (StoredMessage m : ctx.history()) {
                if (m.text() == null || m.text().isBlank()) {
                    continue;
                }
                switch (m.role()) {
                    case "owner" -> out.add(ChatMessage.user(m.text()));
                    case "owner_ingame" -> out.add(ChatMessage.user("（游戏里说）" + m.text()));
                    case "companion" -> out.add(ChatMessage.assistant(m.text()));
                    default -> {
                        // system 之类的不给模型看
                    }
                }
            }
        }
        StringBuilder last = new StringBuilder(stateLine(ctx, names));
        if (userText != null) {
            last.append("\n\n").append(fromOwner ? "主人说：" : "").append(userText);
        }
        out.add(ChatMessage.user(last.toString()));
        return out;
    }
}
