package dev.blockmate.server.hub;

import com.google.gson.JsonObject;
import dev.blockmate.mock.Options;
import dev.blockmate.mock.Simulator;
import dev.blockmate.mock.WsClient;
import dev.blockmate.protocol.Chat;
import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Hello;
import dev.blockmate.protocol.HelloOk;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Peer;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.Reply;
import dev.blockmate.protocol.Status;
import dev.blockmate.server.Config;
import dev.blockmate.server.ServerApp;
import dev.blockmate.server.gateway.ChatRequest;
import dev.blockmate.server.gateway.ModelClient;
import dev.blockmate.server.gateway.ModelReply;
import dev.blockmate.server.gateway.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内把后台、假执行器（任务单 2 的 Simulator）、一个网页客户端、一个假模型连起来，
 * 走一遍手册 5.5 的验收 a–e。
 */
class HubIntegrationTest {

    @TempDir
    Path dir;

    private ServerApp app;
    private final FakeModel model = new FakeModel();
    private final List<ScheduledExecutorService> execs = new ArrayList<>();
    private final List<WsClient> mocks = new ArrayList<>();
    private final List<String> mockConsole = Collections.synchronizedList(new ArrayList<>());

    /** 剧本式的假模型：看最后一句话决定回文字还是工具调用。 */
    static final class FakeModel implements ModelClient {
        final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ModelReply chat(ChatRequest req, Consumer<String> onChunk) {
            requests.add(req);
            String last = req.messages().get(req.messages().size() - 1).content();
            boolean decision = req.tools() != null && req.tools().size() > 1;
            if (decision) {
                if (last.contains("跟我来")) {
                    return new ModelReply("", List.of(new ToolCall("t1", "follow", new JsonObject())), 1, 1, 1);
                }
                if (last.contains("过来")) {
                    return new ModelReply("", List.of(new ToolCall("t2", "come_here", new JsonObject())), 1, 1, 1);
                }
                if (last.contains("记住")) {
                    JsonObject args = new JsonObject();
                    args.addProperty("text", "晚上十点以后不打游戏");
                    onChunk.accept("记下了，等你确认。");
                    return new ModelReply("记下了，等你确认。",
                            List.of(new ToolCall("t3", "remember_preference", args)), 1, 1, 1);
                }
                onChunk.accept("你好呀，");
                onChunk.accept("我是 Blockmate。");
                return new ModelReply("你好呀，我是 Blockmate。", List.of(), 1, 1, 1);
            }
            String t = last.contains("身体没反应") ? "没反应啊，身体不听使唤。" : last.contains("失败") ? "没做成。" : "来了来了。";
            onChunk.accept(t);
            return new ModelReply(t, List.of(), 1, 1, 1);
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    /** 最简网页客户端。 */
    static final class UiClient implements WebSocket.Listener {
        final LinkedBlockingQueue<Envelope> inbox = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        /** reply() 等待期间收到的其他消息，留给 next() 用。 */
        final List<Envelope> stash = new ArrayList<>();
        volatile int closeCode = -1;
        WebSocket ws;

        static UiClient connect(int port, String queryToken, String helloToken) {
            UiClient c = new UiClient();
            c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/ui?token=" + queryToken), c).join();
            c.send(MessageType.HELLO, Hello.ui(helloToken, "test"));
            return c;
        }

        void send(MessageType type, Object body) {
            ws.sendText(ProtocolCodec.encode(ProtocolCodec.envelope(type, Peer.UI, Peer.HUB, body)), true).join();
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                inbox.add(ProtocolCodec.decode(partial.toString()));
                partial.setLength(0);
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
            closeCode = statusCode;
            return null;
        }

        Envelope next(MessageType type, long timeoutMs) throws InterruptedException {
            for (int i = 0; i < stash.size(); i++) {
                if (stash.get(i).type() == type) {
                    return stash.remove(i);
                }
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                Envelope e = inbox.poll(Math.max(1, left), TimeUnit.MILLISECONDS);
                if (e == null) {
                    throw new AssertionError("等 " + type + " 超时");
                }
                if (e.type() == type) {
                    return e;
                }
            }
        }

        /** 收集一条完整回复（分片直到 done）。 */
        String reply(long timeoutMs) throws InterruptedException {
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                Envelope e = inbox.poll(Math.max(1, left), TimeUnit.MILLISECONDS);
                if (e == null) {
                    throw new AssertionError("等回复超时，已收到: " + sb);
                }
                if (e.type() != MessageType.REPLY) {
                    stash.add(e);
                    continue;
                }
                Reply r = ProtocolCodec.bodyAs(e, Reply.class);
                sb.append(r.chunk());
                if (r.done()) {
                    return sb.toString();
                }
            }
        }

        Status statusWhere(java.util.function.Predicate<Status> p, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                Envelope e = next(MessageType.STATUS, Math.max(1, deadline - System.currentTimeMillis()));
                Status s = ProtocolCodec.bodyAs(e, Status.class);
                if (p.test(s)) {
                    return s;
                }
            }
        }
    }

    private Config config() {
        return Config.of(dir, Map.of("token", "t", "history_n", 20, "owner_name", "Steve",
                "timeouts_s", Map.of("ack", 1, "come_here", 90)));
    }

    private void startApp() throws Exception {
        app = ServerApp.start(config(), 0, model);
    }

    private WsClient startMock(boolean silent) {
        Options opts = new Options(URI.create("ws://127.0.0.1:" + app.port() + "/ws/mc"), "t", "Blockmate", "Steve",
                false, silent, 1000);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        execs.add(exec);
        WsClient client = new WsClient(opts.hub(), opts.token(), exec, mockConsole::add);
        Simulator sim = new Simulator(opts, client, mockConsole::add);
        client.attach(sim);
        client.start();
        exec.scheduleAtFixedRate(() -> sim.tick(System.currentTimeMillis()), 100, 100, TimeUnit.MILLISECONDS);
        mocks.add(client);
        return client;
    }

    private static void waitFor(BooleanSupplier cond, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待超时: " + what);
            }
            Thread.sleep(50);
        }
    }

    @AfterEach
    void tearDown() {
        mocks.forEach(WsClient::stop);
        execs.forEach(ScheduledExecutorService::shutdownNow);
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void 验收a到e() throws Exception {
        startApp();

        // 网页先连上：hello 回复里有状态，伙伴离线
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        HelloOk ok = ProtocolCodec.bodyAs(ui.next(MessageType.HELLO, 3000), HelloOk.class);
        assertTrue(ok.ok());
        assertFalse(ok.status().companion().online());

        // a. 启动假执行器，后台认出 executor online，网页状态条变在线
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "executor online + snapshot");
        Status online = ui.statusWhere(s -> s.companion().online() && Boolean.TRUE.equals(s.companion().inWorld()), 5000);
        assertEquals("minecraft:overworld", online.companion().dim());

        // b. 聊天：符合人设的回复，且提示词里带了快照摘要
        ui.send(MessageType.CHAT, new Chat("你好，你叫什么"));
        String greet = ui.reply(10_000);
        assertTrue(greet.contains("Blockmate"), greet);
        ChatRequest decision = model.requests.get(model.requests.size() - 1);
        assertTrue(decision.messages().get(0).content().contains("你在主世界"), decision.messages().get(0).content());
        assertEquals(8, decision.tools().size(), "决策请求带全部工具");

        // c. 跟我来：假执行器打印"收到 follow"，3 秒后结果到达，网页收到"来了"
        ui.send(MessageType.CHAT, new Chat("跟我来"));
        String ack = ui.reply(5000);
        assertEquals("", ack, "发命令时模型没说话，回复为空");
        waitFor(() -> mockConsole.contains("收到 follow"), 3000, "mock 收到 follow");
        ui.statusWhere(s -> s.task() != null && "follow".equals(s.task().name().wire()), 3000);
        String came = ui.reply(8000);
        assertTrue(came.contains("来了"), came);
        ChatRequest say = model.requests.get(model.requests.size() - 1);
        assertEquals(1, say.tools().size(), "结果后的请求不带动作工具");
        assertTrue(say.messages().get(say.messages().size() - 1).content().contains("成功"));

        // 偏好：只写 pending，网页收到待确认列表
        ui.send(MessageType.CHAT, new Chat("记住，晚上十点以后别叫我"));
        ui.reply(5000);
        Envelope prefs = ui.next(MessageType.PREFERENCES, 3000);
        assertTrue(prefs.body().getAsJsonArray("items").size() >= 1);
        assertEquals(1, app.memory().preferences("pending").size());
        assertEquals(0, app.memory().preferences("active").size());

        // d. 换成 --silent 的假执行器：ack 超时（测试里 1 秒），网页收到"没反应"
        mocks.remove(0).stop();
        waitFor(() -> !app.hub().executorOnline(), 5000, "旧执行器下线");
        startMock(true);
        waitFor(() -> app.hub().companionInWorld(), 5000, "silent 执行器上线");
        ui.send(MessageType.CHAT, new Chat("过来"));
        ui.reply(5000);
        String noResponse = ui.reply(6000);
        assertTrue(noResponse.contains("没反应"), noResponse);
        assertTrue(app.hub().currentTask().isEmpty(), "超时后槽位应清空");

        // e. 关掉后台再启动：历史还在，模型能看到上一条
        int port = app.port();
        app.stop();
        app = null;
        mocks.forEach(WsClient::stop);
        app = ServerApp.start(config(), port, model);
        UiClient ui2 = UiClient.connect(app.port(), "t", "t");
        HelloOk ok2 = ProtocolCodec.bodyAs(ui2.next(MessageType.HELLO, 3000), HelloOk.class);
        assertNotNull(ok2.history());
        assertTrue(ok2.history().toString().contains("你好，你叫什么"), ok2.history().toString());
        ui2.send(MessageType.CHAT, new Chat("我们刚才聊了什么"));
        ui2.reply(10_000);
        ChatRequest afterRestart = model.requests.get(model.requests.size() - 1);
        assertTrue(afterRestart.messages().stream().anyMatch(m -> "你好，你叫什么".equals(m.content())),
                "重启后历史要进提示词");
    }

    @Test
    void token不对直接断开() throws Exception {
        startApp();
        UiClient bad = UiClient.connect(app.port(), "wrong", "wrong");
        waitFor(() -> bad.closeCode == 4401, 5000, "4401");
        assertEquals(4401, bad.closeCode);

        UiClient queryOnly = UiClient.connect(app.port(), "t", "wrong");
        HelloOk ok = ProtocolCodec.bodyAs(queryOnly.next(MessageType.HELLO, 3000), HelloOk.class);
        assertTrue(ok.ok(), "查询参数里的 token 对了就行");
    }

    @Test
    void 停按钮不经过模型() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        ui.send(MessageType.CHAT, new Chat("跟我来"));
        ui.reply(5000);
        waitFor(() -> app.hub().currentTask().isPresent(), 3000, "task");
        int before = model.requests.size();
        ui.send(MessageType.STOP, new JsonObject());
        waitFor(() -> mockConsole.contains("收到 stop"), 2000, "mock 收到 stop");
        Envelope notice = ui.next(MessageType.NOTICE, 3000);
        assertTrue(notice.body().get("text").getAsString().contains("停"), notice.body().toString());
        waitFor(() -> app.hub().currentTask().isEmpty(), 3000, "槽位清空");
        assertEquals(before, model.requests.size(), "停按钮不该调模型");
    }
}
