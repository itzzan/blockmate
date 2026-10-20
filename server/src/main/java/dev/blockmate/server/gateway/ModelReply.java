package dev.blockmate.server.gateway;

import java.util.List;

/** 模型的完整回复（流式分片已经通过回调给出去了）。 */
public record ModelReply(String text, List<ToolCall> toolCalls, int promptTokens, int completionTokens,
                         long durationMs) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
