package dev.blockmate.server.brain;

import dev.blockmate.server.gateway.ChatMessage;
import dev.blockmate.server.memory.Episode;
import dev.blockmate.server.memory.StoredMessage;

import java.util.ArrayList;
import java.util.List;

/** 组装提示词（细则第 9 节的顺序）。 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /**
     * system 部分只放一次对话里基本不变的东西（人设、偏好、日记），不放每轮都变的状态句，
     * 这样 Ollama 的前缀缓存能一直命中（2026-10-22：状态句在 system 里时每轮重算 1000 多 token，首字 9 秒）。
     */
    public static String systemPrompt(String persona, BrainContext ctx) {
        StringBuilder sb = new StringBuilder(persona.strip()).append("\n\n");
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
        return sb.toString().strip();
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
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(systemPrompt(persona, ctx)));
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
