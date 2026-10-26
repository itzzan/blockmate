package dev.blockmate.server.gateway;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import dev.blockmate.server.brain.Tools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用一个假的 HTTP 服务模拟 Ollama 的 /api/chat 流。 */
class OllamaNativeClientTest {

    private HttpServer server;

    private URI serve(int status, String body, long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                // ignore
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 解析文本分片和工具调用() throws Exception {
        String ndjson = """
                {"model":"m","message":{"role":"assistant","content":"来"},"done":false}
                {"model":"m","message":{"role":"assistant","content":"了。"},"done":false}
                {"model":"m","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"follow","arguments":{}}},{"function":{"name":"pick_up","arguments":{"item":"minecraft:oak_log","radius":8}}}]},"done":false}
                {"model":"m","message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":120,"eval_count":9}
                """;
        URI base = serve(200, ndjson, 0);
        OllamaNativeClient c = new OllamaNativeClient(base, Duration.ofSeconds(5), Duration.ofSeconds(10));
        List<String> chunks = new ArrayList<>();
        ModelReply r = c.chat(new ChatRequest("m", List.of(ChatMessage.user("跟我来")), Tools.all()), chunks::add);
        assertEquals("来了。", r.text());
        assertEquals(List.of("来", "了。"), chunks);
        assertEquals(2, r.toolCalls().size());
        assertEquals("follow", r.toolCalls().get(0).name());
        assertEquals("minecraft:oak_log", r.toolCalls().get(1).arguments().get("item").getAsString());
        assertEquals(120, r.promptTokens());
        assertEquals(9, r.completionTokens());
    }

    @Test
    void 请求体带think_false和tools() {
        JsonObject body = OllamaNativeClient.buildBody(new ChatRequest("companion-local",
                List.of(ChatMessage.system("s"), ChatMessage.user("u")), Tools.all()));
        assertEquals("companion-local", body.get("model").getAsString());
        assertTrue(body.get("stream").getAsBoolean());
        assertEquals(false, body.get("think").getAsBoolean());
        assertEquals(20, body.getAsJsonArray("tools").size());
        assertEquals(2, body.getAsJsonArray("messages").size());
        JsonObject noTools = OllamaNativeClient.buildBody(new ChatRequest("m", List.of(ChatMessage.user("u")), null));
        assertTrue(!noTools.has("tools"));
    }

    @Test
    void 非200回复报BAD_RESPONSE() throws Exception {
        URI base = serve(500, "{\"error\":\"boom\"}", 0);
        OllamaNativeClient c = new OllamaNativeClient(base, Duration.ofSeconds(5), Duration.ofSeconds(10));
        ModelException e = assertThrows(ModelException.class,
                () -> c.chat(new ChatRequest("m", List.of(ChatMessage.user("u")), null), s -> { }));
        assertEquals(ModelException.Kind.BAD_RESPONSE, e.kind());
    }

    @Test
    void 首字超时报TIMEOUT() throws Exception {
        URI base = serve(200, "{\"message\":{\"content\":\"x\"},\"done\":true}\n", 2500);
        OllamaNativeClient c = new OllamaNativeClient(base, Duration.ofMillis(500), Duration.ofSeconds(10));
        ModelException e = assertThrows(ModelException.class,
                () -> c.chat(new ChatRequest("m", List.of(ChatMessage.user("u")), null), s -> { }));
        assertEquals(ModelException.Kind.TIMEOUT, e.kind());
    }

    @Test
    void 连不上报CONNECT() {
        OllamaNativeClient c = new OllamaNativeClient(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        ModelException e = assertThrows(ModelException.class,
                () -> c.chat(new ChatRequest("m", List.of(ChatMessage.user("u")), null), s -> { }));
        assertEquals(ModelException.Kind.CONNECT, e.kind());
    }

    @Test
    void openai兼容流也能解析() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"来"}}]}

                data: {"choices":[{"delta":{"content":"了"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"follow","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3}}

                data: [DONE]
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OllamaOpenAiClient c = new OllamaOpenAiClient(base, Duration.ofSeconds(5), Duration.ofSeconds(10));
        ModelReply r = c.chat(new ChatRequest("m", List.of(ChatMessage.user("跟我来")), Tools.all()), s -> { });
        assertEquals("来了", r.text());
        assertEquals(1, r.toolCalls().size());
        assertEquals("follow", r.toolCalls().get(0).name());
        assertEquals("call_1", r.toolCalls().get(0).id());
        assertEquals(10, r.promptTokens());
    }
}
