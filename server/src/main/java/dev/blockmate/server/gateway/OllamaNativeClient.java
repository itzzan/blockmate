package dev.blockmate.server.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockmate.protocol.ProtocolCodec;
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
import java.util.List;
import java.util.function.Consumer;

/**
 * Ollama 原生接口 /api/chat（细则第 10 节）：stream:true，think:false，带 tools。
 * 回复是一行一个 JSON（NDJSON）。
 */
public final class OllamaNativeClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaNativeClient.class);

    private final HttpClient http;
    private final URI endpoint;
    private final Duration firstToken;
    private final Duration total;

    public OllamaNativeClient(URI baseUrl, Duration firstToken, Duration total) {
        // 固定 HTTP/1.1：Ollama 不支持 h2c 升级，省掉每次连接的 Upgrade 协商，也少一个排错变量
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        this.endpoint = baseUrl.resolve("/api/chat");
        this.firstToken = firstToken;
        this.total = total;
    }

    @Override
    public String name() {
        return "ollama-native";
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
            String err = readAll(resp.body());
            throw new ModelException(ModelException.Kind.BAD_RESPONSE,
                    "模型返回 HTTP " + resp.statusCode() + " " + headerSummary(resp) + ": " + trim(err));
        }
        return readStream(resp.body(), start, onChunk);
    }

    ModelReply readStream(InputStream in, long start, Consumer<String> onChunk) throws ModelException {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        long deadline = start + total.toMillis();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.currentTimeMillis() > deadline) {
                    throw new ModelException(ModelException.Kind.TIMEOUT, "一次回答超过 " + total.toSeconds() + " 秒");
                }
                if (line.isBlank()) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    throw new ModelException(ModelException.Kind.BAD_RESPONSE, "模型回复不是 JSON: " + trim(line));
                }
                if (o.has("error")) {
                    throw new ModelException(ModelException.Kind.BAD_RESPONSE, "模型报错: " + o.get("error").getAsString());
                }
                JsonObject msg = o.has("message") && o.get("message").isJsonObject() ? o.getAsJsonObject("message") : null;
                if (msg != null) {
                    String content = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
                    if (!content.isEmpty()) {
                        text.append(content);
                        onChunk.accept(content);
                    }
                    if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
                        for (JsonElement tc : msg.getAsJsonArray("tool_calls")) {
                            calls.add(parseToolCall(tc.getAsJsonObject(), calls.size()));
                        }
                    }
                }
                if (o.has("done") && o.get("done").getAsBoolean()) {
                    promptTokens = o.has("prompt_eval_count") ? o.get("prompt_eval_count").getAsInt() : 0;
                    completionTokens = o.has("eval_count") ? o.get("eval_count").getAsInt() : 0;
                    break;
                }
            }
        } catch (IOException e) {
            throw new ModelException(ModelException.Kind.CONNECT, "读模型回复时断了: " + e.getMessage(), e);
        }
        long ms = System.currentTimeMillis() - start;
        log.info("model_call api=native ms={} in_tok={} out_tok={} tools={} text_len={}", ms, promptTokens,
                completionTokens, calls.size(), text.length());
        return new ModelReply(text.toString(), calls, promptTokens, completionTokens, ms);
    }

    static ToolCall parseToolCall(JsonObject tc, int index) {
        JsonObject fn = tc.has("function") ? tc.getAsJsonObject("function") : tc;
        String name = fn.has("name") ? fn.get("name").getAsString() : "";
        JsonObject args = new JsonObject();
        if (fn.has("arguments")) {
            JsonElement a = fn.get("arguments");
            if (a.isJsonObject()) {
                args = a.getAsJsonObject();
            } else if (a.isJsonPrimitive() && a.getAsJsonPrimitive().isString()) {
                try {
                    args = JsonParser.parseString(a.getAsString()).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    args = new JsonObject();
                }
            }
        }
        String id = tc.has("id") && !tc.get("id").isJsonNull() ? tc.get("id").getAsString() : "call_" + index;
        return new ToolCall(id, name, args);
    }

    static JsonObject buildBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("stream", true);
        body.addProperty("think", false);
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
                    fn.add("arguments", c.arguments() == null ? new JsonObject() : c.arguments());
                    JsonObject tc = new JsonObject();
                    tc.add("function", fn);
                    tcs.add(tc);
                }
                o.add("tool_calls", tcs);
            }
            if (m.toolName() != null) {
                o.addProperty("tool_name", m.toolName());
            }
            messages.add(o);
        }
        body.add("messages", messages);
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.add("tools", request.tools());
        }
        return body;
    }

    /** 非 200 时把能说明"是谁回的"的响应头带进日志（server / via / content-type），排查中间层问题用。 */
    static String headerSummary(HttpResponse<?> resp) {
        StringBuilder sb = new StringBuilder("[");
        for (String h : new String[] {"server", "via", "content-type", "content-length"}) {
            resp.headers().firstValue(h).ifPresent(v -> sb.append(h).append('=').append(v).append(' '));
        }
        return sb.toString().trim() + "]";
    }

    private static String readAll(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String trim(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 工具用：Gson 实例。 */
    static com.google.gson.Gson gson() {
        return ProtocolCodec.gson();
    }
}
