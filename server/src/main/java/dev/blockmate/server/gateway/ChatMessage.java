package dev.blockmate.server.gateway;

import java.util.List;

/** 发给模型的一条消息。role：system / user / assistant / tool。 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolName) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    public static ChatMessage assistantToolCalls(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, calls, null);
    }

    public static ChatMessage tool(String toolName, String content) {
        return new ChatMessage("tool", content, null, toolName);
    }
}
