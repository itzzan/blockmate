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

    public static String systemPrompt(String persona, BrainContext ctx, ItemNames names) {
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
        sb.append("现在的状态：").append(SnapshotSummary.describe(ctx.snapshot(), ctx.executorOnline(), names));
        return sb.toString();
    }

    /** system + 最近对话 + 这次的输入。 */
    public static List<ChatMessage> build(String persona, BrainContext ctx, ItemNames names, String userText) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(systemPrompt(persona, ctx, names)));
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
        if (userText != null) {
            out.add(ChatMessage.user(userText));
        }
        return out;
    }
}
