package dev.blockmate.server.brain;

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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Brain（手册 5.5 第 3 条）：决定说什么、做什么；结果到了说一句话；每天写日记。 */
public final class Brain {

    private static final Logger log = LoggerFactory.getLogger(Brain.class);

    private final ModelClient client;
    private final String model;
    private final Persona persona;
    private final ItemNames names;
    private final String ownerName;
    private final String companionName;

    public Brain(ModelClient client, String model, Persona persona, ItemNames names, String ownerName,
                 String companionName) {
        this.client = client;
        this.model = model;
        this.persona = persona;
        this.names = names;
        this.ownerName = ownerName;
        this.companionName = companionName;
    }

    /** 决策请求：带全部工具。 */
    public ModelReply decide(String userText, BrainContext ctx, Consumer<String> onChunk) throws ModelException {
        List<ChatMessage> messages = PromptBuilder.build(persona.text(), ctx, names, userText);
        ModelReply reply = client.chat(new ChatRequest(model, messages, Tools.all()), onChunk);
        log.info("decide text_len={} tools={}", reply.text().length(), reply.toolCalls().size());
        return reply;
    }

    /** 结果到达后的"说一句话"请求：不带动作工具（手册 5.5 第 3 条）。 */
    public ModelReply sayAfterResult(CommandName name, Result result, BrainContext ctx, Consumer<String> onChunk)
            throws ModelException {
        String prompt = "[系统] 你刚才做的「" + SnapshotSummary.taskName(name) + "」结束了：" + describeResult(name, result)
                + "。用一两句话告诉主人，像平时说话那样，不要复述这段系统提示。"
                + "如果上面写的是失败，就如实说没做成和原因，绝不能说成已经做好了。";
        List<ChatMessage> messages = PromptBuilder.build(persona.text(), ctx, names, prompt, false);
        ModelReply reply = client.chat(new ChatRequest(model, messages, Tools.chatOnly()), onChunk);
        log.info("say_after_result name={} text_len={}", name.wire(), reply.text().length());
        return reply;
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
        for (StoredEvent e : events) {
            sb.append("事件 ").append(e.kind());
            if (e.commandId() != null) {
                sb.append("（任务 ").append(e.commandId(), 0, Math.min(8, e.commandId().length())).append("）");
            }
            sb.append('\n');
        }
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(persona.text()));
        prompt.add(ChatMessage.user(sb.toString()));
        ModelReply reply = client.chat(new ChatRequest(model, prompt, null), chunk -> { });
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
            return "成功";
        }
        if (r.status() == ResultStatus.CANCELLED) {
            return "被新的指令打断，没做完";
        }
        return "失败，原因：" + reasonZh(r.reason());
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
        };
    }
}
