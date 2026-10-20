package dev.blockmate.server.gateway;

import com.google.gson.JsonArray;

import java.util.List;

/** 一次模型请求。tools 为 null 或空表示不带工具。 */
public record ChatRequest(String model, List<ChatMessage> messages, JsonArray tools) {
}
