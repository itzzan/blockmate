package dev.blockmate.server.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容接口 /v1/chat/completions（细则第 10 节的第二实现，config 里 ollama.api=openai 时用）。
 * 回复是 SSE：每行 "data: {...}"，最后 "data: [DONE]"。
 */
public final class OllamaOpenAiClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaOpenAiClient.class);

    private final HttpClient http;
    private final URI endpoint;
    private final Duration firstToken;
    private final Duration total;

    public OllamaOpenAiClient(URI baseUrl, Duration firstToken, Duration total) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.endpoint = baseUrl.resolve("/v1/chat/completions");
        this.firstToken = firstToken;
        this.total = total;
    }

    @Override
    public String name() {
        return "ollama-openai";
    }

    @Override
    public ModelReply chat(ChatRequest request, Consumer<String> onChunk) throws ModelException {
        JsonObject body = buildBody(request);
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(firstToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        long start = System.currentTimeMillis();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new ModelException(ModelException.Kind.TIMEOUT, "等首字超过 " + firstToken.toSeconds() + " 秒", e);
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.CONNECT, "连不上模型: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Kind.CONNECT, "被中断", e);
        }
        if (resp.statusCode() != 200) {
            throw new ModelException(ModelException.Kind.BAD_RESPONSE, "模型返回 HTTP " + resp.statusCode());
        }
        return readStream(resp.body(), start, onChunk);
    }

    ModelReply readStream(InputStream in, long start, Consumer<String> onChunk) throws ModelException {
        StringBuilder text = new StringBuilder();
        Map<Integer, String[]> partial = new LinkedHashMap<>(); // index -> [id, name, argumentsSoFar]
        int promptTokens = 0;
        int completionTokens = 0;
        long deadline = start + total.toMillis();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.currentTimeMillis() > deadline) {
                    throw new ModelException(ModelException.Kind.TIMEOUT, "一次回答超过 " + total.toSeconds() + " 秒");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    break;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(data).getAsJsonObject();
                } catch (RuntimeException e) {
                    throw new ModelException(ModelException.Kind.BAD_RESPONSE, "模型回复不是 JSON");
                }
                if (o.has("error")) {
                    throw new ModelException(ModelException.Kind.BAD_RESPONSE, "模型报错: " + o.get("error"));
                }
                if (o.has("usage") && o.get("usage").isJsonObject()) {
                    JsonObject u = o.getAsJsonObject("usage");
                    promptTokens = u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : promptTokens;
                    completionTokens = u.has("completion_tokens") ? u.get("completion_tokens").getAsInt() : completionTokens;
                }
                if (!o.has("choices")) {
                    continue;
                }
                for (JsonElement ch : o.getAsJsonArray("choices")) {
                    JsonObject delta = ch.getAsJsonObject().has("delta") ? ch.getAsJsonObject().getAsJsonObject("delta") : null;
                    if (delta == null) {
                        continue;
                    }
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String c = delta.get("content").getAsString();
                        if (!c.isEmpty()) {
                            text.append(c);
                            onChunk.accept(c);
                        }
                    }
                    if (delta.has("tool_calls")) {
                        for (JsonElement tcEl : delta.getAsJsonArray("tool_calls")) {
                            JsonObject tc = tcEl.getAsJsonObject();
                            int index = tc.has("index") ? tc.get("index").getAsInt() : partial.size();
                            String[] slot = partial.computeIfAbsent(index, k -> new String[] {"call_" + k, "", ""});
                            if (tc.has("id") && !tc.get("id").isJsonNull()) {
                                slot[0] = tc.get("id").getAsString();
                            }
                            if (tc.has("function")) {
                                JsonObject fn = tc.getAsJsonObject("function");
                                if (fn.has("name") && !fn.get("name").isJsonNull()) {
                                    slot[1] = slot[1] + fn.get("name").getAsString();
                                }
                                if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                    JsonElement a = fn.get("arguments");
                                    slot[2] = slot[2] + (a.isJsonPrimitive() ? a.getAsString() : a.toString());
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.CONNECT, "读模型回复时断了: " + e.getMessage(), e);
        }
        List<ToolCall> calls = new ArrayList<>();
        for (String[] slot : partial.values()) {
            JsonObject args = new JsonObject();
            if (!slot[2].isBlank()) {
                try {
                    args = JsonParser.parseString(slot[2]).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    args = new JsonObject();
                }
            }
            calls.add(new ToolCall(slot[0], slot[1], args));
        }
        long ms = System.currentTimeMillis() - start;
        log.info("model_call api=openai ms={} in_tok={} out_tok={} tools={} text_len={}", ms, promptTokens,
                completionTokens, calls.size(), text.length());
        return new ModelReply(text.toString(), calls, promptTokens, completionTokens, ms);
    }

    static JsonObject buildBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        body.add("stream_options", streamOptions);
        JsonArray messages = new JsonArray();
        for (ChatMessage m : request.messages()) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content() == null ? "" : m.content());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                JsonArray tcs = new JsonArray();
                for (ToolCall c : m.toolCalls()) {
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", c.name());
                    fn.addProperty("arguments", (c.arguments() == null ? new JsonObject() : c.arguments()).toString());
                    JsonObject tc = new JsonObject();
                    tc.addProperty("id", c.id());
                    tc.addProperty("type", "function");
                    tc.add("function", fn);
                    tcs.add(tc);
                }
                o.add("tool_calls", tcs);
            }
            if (m.toolName() != null) {
                o.addProperty("name", m.toolName());
            }
            messages.add(o);
        }
        body.add("messages", messages);
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.add("tools", request.tools());
        }
        return body;
    }
}
