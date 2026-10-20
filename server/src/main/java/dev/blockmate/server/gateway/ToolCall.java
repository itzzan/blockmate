package dev.blockmate.server.gateway;

import com.google.gson.JsonObject;

/** 模型返回的一次工具调用。 */
public record ToolCall(String id, String name, JsonObject arguments) {
}
