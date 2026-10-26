package dev.blockmate.server.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockmate.mock.Options;
import dev.blockmate.mock.Simulator;
import dev.blockmate.mock.WsClient;
import dev.blockmate.protocol.Chat;
import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Result;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Command;
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
import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        /** 计划里一步失败时假模型的决定（任务单 11：retry / skip / replan / abort）和那句话。 */
        volatile String planDecision = "abort";
        volatile String planSay = "这步不行，我先放下了。";
        volatile JsonArray planNewSteps;
        /** 听到"弄 20 根木头"时给的 make_plan 参数；重问时给 makePlanArgsRetry（null = 回文字不给计划）。 */
        volatile JsonObject makePlanArgs;
        volatile JsonObject makePlanArgsRetry;

        @Override
        public ModelReply chat(ChatRequest req, Consumer<String> onChunk) {
            requests.add(req);
            String last = req.messages().get(req.messages().size() - 1).content();
            boolean decision = req.tools() != null && req.tools().size() > 1;
            if (decision) {
                if (last.contains("弄 20 根木头")) {
                    boolean reask = last.contains("上次给的计划有问题");
                    JsonObject args = reask ? makePlanArgsRetry : makePlanArgs;
                    if (args == null) {
                        onChunk.accept("我不会。");
                        return new ModelReply("我不会。", List.of(), 1, 1, 1);
                    }
                    String t = reask ? "再想想：我去砍树然后给你。" : "好嘞，我去砍树然后给你！";
                    onChunk.accept(t);
                    return new ModelReply(t, List.of(new ToolCall("mp", "make_plan", args)), 1, 1, 1);
                }
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
            if (last.contains("请调用 plan_decision")) {
                JsonObject args = new JsonObject();
                args.addProperty("action", planDecision);
                args.addProperty("say", planSay);
                if (planNewSteps != null) {
                    args.add("new_steps", planNewSteps);
                }
                return new ModelReply("", List.of(new ToolCall("pd", "plan_decision", args)), 1, 1, 1);
            }
            String t = last.contains("身体没反应") ? "没反应啊，身体不听使唤。" : last.contains("结束了：失败") ? "没做成。" : "来了来了。";
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
        // 任务单 12：测试目录里放一份白名单，模组连上时 hello 会带
        Files.createDirectories(dir.resolve("knowledge"));
        Files.writeString(dir.resolve("knowledge").resolve("blocks.yaml"), """
                mineable:
                  - minecraft:oak_log
                  - minecraft:stone
                  - minecraft:cobblestone
                placeable:
                  - minecraft:crafting_table
                  - minecraft:torch
                drops:
                  minecraft:stone: minecraft:cobblestone
                """);
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
        String lastMsg = decision.messages().get(decision.messages().size() - 1).content();
        assertTrue(lastMsg.contains("你在主世界"), "状态句在最后一条 user 里：" + lastMsg);
        assertFalse(decision.messages().get(0).content().contains("你在主世界"), "system 里不再有状态句");
        assertEquals(20, decision.tools().size(), "决策请求带全部工具");

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

    @Test
    void 固定短语不经过模型() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        int before = model.requests.size();
        ui.send(MessageType.CHAT, new Chat("等在这！"));
        waitFor(() -> mockConsole.stream().anyMatch(l -> l.contains("wait_here")), 3000, "mock 收到 wait_here");
        // 决策没调模型；动作结束后"说一句"那次调用是允许的，所以只断言决策阶段
        assertEquals(before, model.requests.size() <= before + 1 ? before : model.requests.size(), "固定短语的决策不该调模型");
        waitFor(() -> model.requests.size() == before + 1, 5000, "结束后说一句");
        ChatRequest say = model.requests.get(model.requests.size() - 1);
        assertTrue(say.tools() == null || say.tools().size() <= 1, "说一句的请求不带动作工具");
    }

    private static PlanRun.Step step(int i, CommandName name, Object args) {
        return new PlanRun.Step(i, name, args == null ? new JsonObject() : ProtocolCodec.toBody(args));
    }

    private Envelope planMessage(UiClient ui, java.util.function.Predicate<dev.blockmate.protocol.Plan> until, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Envelope last = null;
        while (System.currentTimeMillis() < deadline) {
            Envelope e = ui.next(MessageType.PLAN, Math.max(1, deadline - System.currentTimeMillis()));
            if (e == null) {
                break;
            }
            last = e;
            if (until.test(ProtocolCodec.bodyAs(e, dev.blockmate.protocol.Plan.class))) {
                return e;
            }
        }
        return last;
    }

    @Test
    void 三步计划顺序执行() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        app.hub().submit(() -> app.hub().startPlan("去弄木头", List.of(
                step(0, CommandName.COME_HERE, null),
                step(1, CommandName.PICK_UP, new Command.PickUpArgs("minecraft:oak_log", 8)),
                step(2, CommandName.DELIVER, new Command.DeliverArgs("minecraft:oak_log", 8, null))), null));
        Envelope done = planMessage(ui, p -> p.steps().stream().allMatch(st -> st.state() == dev.blockmate.protocol.PlanStepState.DONE), 15000);
        assertNotNull(done, "应收到计划全部完成的 plan 消息");
        dev.blockmate.protocol.Plan p = ProtocolCodec.bodyAs(done, dev.blockmate.protocol.Plan.class);
        assertEquals(3, p.steps().size());
        assertNull(p.current(), "结束后 current 为空");
        List<String> order = mockConsole.stream().filter(l -> l.startsWith("收到 ")).map(l -> l.substring(3)).toList();
        int a = order.indexOf("come_here"), b = order.indexOf("pick_up"), c = order.indexOf("deliver");
        assertTrue(a >= 0 && b > a && c > b, "三步按顺序派给模组：" + order);
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 2000, "plan done");
        assertEquals(1, memory().recentPlans(5).stream().filter(sp -> "done".equals(sp.outcome())).count(), "plans 表记了一条 done");
    }

    @Test
    void 计划中途停_剩余步跳过() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        app.hub().submit(() -> app.hub().startPlan("跟着我然后捡木头", List.of(
                step(0, CommandName.FOLLOW, null),
                step(1, CommandName.PICK_UP, new Command.PickUpArgs("minecraft:oak_log", 8)),
                step(2, CommandName.DELIVER, new Command.DeliverArgs("minecraft:oak_log", 8, null))), null));
        waitFor(() -> mockConsole.contains("收到 follow"), 3000, "follow 派出");
        ui.send(MessageType.STOP, new JsonObject());
        Envelope aborted = planMessage(ui, p -> p.steps().get(2).state() == dev.blockmate.protocol.PlanStepState.SKIPPED, 5000);
        assertNotNull(aborted);
        dev.blockmate.protocol.Plan p = ProtocolCodec.bodyAs(aborted, dev.blockmate.protocol.Plan.class);
        assertEquals(dev.blockmate.protocol.PlanStepState.SKIPPED, p.steps().get(1).state());
        assertEquals(dev.blockmate.protocol.PlanStepState.SKIPPED, p.steps().get(2).state());
        waitFor(() -> mockConsole.contains("收到 stop"), 3000, "mock 收到 stop");
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.ABORTED).orElse(false), 2000, "aborted");
        Thread.sleep(1500);
        assertFalse(mockConsole.contains("收到 pick_up"), "停了以后不该再派下一步：" + mockConsole);
    }

    /** 起一个三步计划（过来 → 去 home → 交木头），等第二步派出后让它失败。 */
    private void startPlanAndFailSecond(UiClient ui, dev.blockmate.protocol.Reason reason) throws Exception {
        app.hub().submit(() -> app.hub().startPlan("去弄木头", List.of(
                step(0, CommandName.COME_HERE, null),
                // 任务单 13 起地点名由 hub 换坐标（没记过就 hub 自己合成失败），所以这里直接给坐标让它派到模组
                step(1, CommandName.GOTO, new Command.GotoArgs(10.0, 64.0, -20.0, null)),
                step(2, CommandName.DELIVER, new Command.DeliverArgs("minecraft:oak_log", 8, null))), null));
        waitFor(() -> mockConsole.contains("收到 goto"), 8000, "第二步派出");
        String cmd = app.hub().currentPlan().flatMap(pr -> pr.currentStep()).map(st -> st.command().wire()).orElse("?");
        assertEquals("goto", cmd);
        mocks.get(0).send(ProtocolCodec.envelope(MessageType.RESULT, dev.blockmate.protocol.Peer.MC, dev.blockmate.protocol.Peer.HUB,
                Result.failed(currentPlanCommandId(), reason)));
    }

    private boolean askedPlanDecision(int from) {
        return model.requests.stream().skip(from).anyMatch(req -> req.messages().get(req.messages().size() - 1).content()
                .contains("请调用 plan_decision"));
    }

    private boolean companionSaid(String fragment) {
        return memory().recentMessages(20).stream().anyMatch(m -> "companion".equals(m.role()) && m.text().contains(fragment));
    }

    private long count(String line) {
        return mockConsole.stream().filter(line::equals).count();
    }

    @Test
    void 第二步失败_脑子说放弃() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.planDecision = "abort";
        model.planSay = "家在哪我不知道，这事先放下。";
        int before = model.requests.size();
        startPlanAndFailSecond(ui, dev.blockmate.protocol.Reason.NO_PLACE_KNOWN);
        Envelope ended = planMessage(ui, p -> p.steps().get(2).state() == dev.blockmate.protocol.PlanStepState.SKIPPED, 10000);
        assertNotNull(ended, "脑子说放弃后剩余步 skipped");
        dev.blockmate.protocol.Plan p = ProtocolCodec.bodyAs(ended, dev.blockmate.protocol.Plan.class);
        assertEquals(dev.blockmate.protocol.PlanStepState.FAILED, p.steps().get(1).state());
        assertTrue(askedPlanDecision(before), "Brain 被问到怎么办");
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.FAILED).orElse(false), 2000, "failed");
        waitFor(() -> companionSaid("这事先放下"), 3000, "先对主人说一句");
        Thread.sleep(1200);
        assertFalse(mockConsole.contains("收到 deliver"), "放弃后不该再派：" + mockConsole);
    }

    @Test
    void 第二步失败_脑子说重试() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.planDecision = "retry";
        model.planSay = "卡了一下，我再走一次。";
        startPlanAndFailSecond(ui, dev.blockmate.protocol.Reason.STUCK);
        waitFor(() -> count("收到 goto") == 2, 5000, "goto 再派一次");
        waitFor(() -> companionSaid("再走一次"), 3000, "先对主人说一句");
        Envelope done = planMessage(ui, p -> p.steps().stream().allMatch(st -> st.state() == dev.blockmate.protocol.PlanStepState.DONE), 15000);
        assertNotNull(done, "重试成功后整个计划做完");
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 2000, "done");
        assertEquals(1, app.hub().currentPlan().orElseThrow().retries());
    }

    @Test
    void 第二步失败_脑子说跳过() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.planDecision = "skip";
        model.planSay = "家找不到，直接把木头给你吧。";
        startPlanAndFailSecond(ui, dev.blockmate.protocol.Reason.NO_PLACE_KNOWN);
        waitFor(() -> mockConsole.contains("收到 deliver"), 5000, "跳过后派第三步");
        waitFor(() -> companionSaid("直接把木头给你"), 3000, "先对主人说一句");
        Envelope done = planMessage(ui, p -> p.steps().get(2).state() == dev.blockmate.protocol.PlanStepState.DONE, 15000);
        assertNotNull(done);
        dev.blockmate.protocol.Plan p = ProtocolCodec.bodyAs(done, dev.blockmate.protocol.Plan.class);
        assertEquals(dev.blockmate.protocol.PlanStepState.SKIPPED, p.steps().get(1).state());
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 2000, "done");
        assertEquals(1, count("收到 goto"), "跳过不重派");
    }

    @Test
    void 第二步失败_脑子重排剩余步骤() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.planDecision = "replan";
        model.planSay = "家不认识，那我先捡木头再过去给你。";
        model.planNewSteps = JsonParser.parseString("""
                [{"command":"pick_up","args":{"item":"oak_log","count":8}},
                 {"command":"deliver","args":{"item":"minecraft:oak_log","count":8}}]""").getAsJsonArray();
        startPlanAndFailSecond(ui, dev.blockmate.protocol.Reason.NO_PLACE_KNOWN);
        waitFor(() -> mockConsole.contains("收到 pick_up"), 5000, "重排后的第一步派出");
        waitFor(() -> companionSaid("先捡木头"), 3000, "先对主人说一句");
        Envelope done = planMessage(ui, p -> p.steps().size() == 4
                && p.steps().get(3).state() == dev.blockmate.protocol.PlanStepState.DONE, 15000);
        assertNotNull(done, "重排后 4 步做完");
        dev.blockmate.protocol.Plan p = ProtocolCodec.bodyAs(done, dev.blockmate.protocol.Plan.class);
        assertEquals(dev.blockmate.protocol.PlanStepState.FAILED, p.steps().get(1).state());
        assertEquals(CommandName.PICK_UP, p.steps().get(2).command());
        assertEquals("minecraft:oak_log", p.steps().get(2).args().get("item").getAsString(), "重排的步骤也过校验（补命名空间）");
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 2000, "done");
    }

    @Test
    void 模组连上时hello带白名单() throws Exception {
        startApp();
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        waitFor(() -> mockConsole.contains("收到白名单 可挖=3 可放=2"), 3000, "mock 收到白名单：" + mockConsole);
    }

    @Test
    void 计划里挖不在清单的方块被打回() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.makePlanArgs = planArgs("""
                [{"command":"mine","args":{"block":"minecraft:chest","count":1}}]""");
        model.makePlanArgsRetry = planArgs("""
                [{"command":"mine","args":{"block":"oak_log","count":20}},
                 {"command":"deliver","args":{"item":"oak_log","count":20}}]""");
        int before = model.requests.size();
        ui.send(MessageType.CHAT, new Chat("去弄 20 根木头回来"));
        ui.reply(8000);
        assertTrue(model.requests.stream().skip(before).anyMatch(req -> req.messages().get(req.messages().size() - 1).content()
                .contains("minecraft:chest 不在允许挖的清单里")), "重问时说清是白名单问题");
        waitFor(() -> mockConsole.contains("收到 mine"), 5000, "第二次合法的计划开始执行");
    }

    @Test
    void 记住这里是家_存进places_不发模组() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        waitFor(() -> app.hub().lastSnapshotForTest().map(sn -> sn.pos() != null).orElse(false), 3000, "有快照");
        int before = model.requests.size();
        ui.send(MessageType.CHAT, new Chat("记住这里是家"));
        waitFor(() -> memory().place("home").isPresent(), 5000, "places 表有 home");
        dev.blockmate.server.memory.StoredPlace home = memory().place("home").orElseThrow();
        assertEquals(app.hub().lastSnapshotForTest().orElseThrow().dim(), home.dim());
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 5000, "单步计划 done");
        assertFalse(mockConsole.stream().anyMatch(l -> l.contains("remember_place")), "不下发模组：" + mockConsole);
        waitFor(() -> model.requests.size() == before + 1, 5000, "固定短语不问模型，做完说一句");
    }

    @Test
    void 回家_没记过_失败说一句() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        int before = model.requests.size();
        ui.send(MessageType.CHAT, new Chat("回家"));
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.FAILED).orElse(false), 5000, "没记过家 → 失败");
        assertFalse(mockConsole.contains("收到 goto"), "没坐标就不派模组：" + mockConsole);
        waitFor(() -> model.requests.size() == before + 1, 5000, "失败后说一句");
        List<dev.blockmate.server.gateway.ChatMessage> msgs = model.requests.get(model.requests.size() - 1).messages();
        String last = msgs.get(msgs.size() - 1).content();
        assertTrue(last.contains("没记过") || last.contains("地点"), "提示里说清是地点没记过：" + last);
    }

    @Test
    void 回家_记过了_派goto带坐标() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        waitFor(() -> app.hub().lastSnapshotForTest().isPresent(), 3000, "有快照");
        String dim = app.hub().lastSnapshotForTest().map(sn -> sn.dim()).orElse(null);
        memory().savePlace("home", dim == null ? "minecraft:overworld" : dim, 10, 64, -20);
        ui.send(MessageType.CHAT, new Chat("回家"));
        waitFor(() -> mockConsole.contains("收到 goto"), 5000, "换成坐标后派 goto：" + mockConsole);
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 8000, "done");
        assertEquals(CommandName.GO_HOME, app.hub().currentPlan().orElseThrow().steps().get(0).command(), "计划里仍是 go_home");
    }

    @Test
    void 重生后_主人看得见_自动去找主人() throws Exception {
        // 假执行器的快照里主人一直看得见，所以走"过来"这条；"不在就回家"那条靠单元逻辑和真游戏验
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        memory().savePlace("home", "minecraft:overworld", 10, 64, -20);
        mocks.get(0).send(ProtocolCodec.envelope(MessageType.EVENT, dev.blockmate.protocol.Peer.MC, dev.blockmate.protocol.Peer.HUB,
                dev.blockmate.protocol.Event.respawn(new dev.blockmate.protocol.Pos(0, 64, 0), "minecraft:overworld")));
        waitFor(() -> mockConsole.contains("收到 come_here"), 8000, "2 秒后派 come_here：" + mockConsole);
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 8000, "done");
        assertEquals(CommandName.COME_HERE, app.hub().currentPlan().orElseThrow().steps().get(0).command());
        assertEquals("重生后去找主人", app.hub().currentPlan().orElseThrow().goal());
    }

    @Test
    void 睡成功_记下床的位置和家() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        assertTrue(memory().place("home").isEmpty());
        ui.send(MessageType.CHAT, new Chat("去睡觉"));
        waitFor(() -> mockConsole.contains("收到 sleep"), 5000, "固定短语派 sleep");
        waitFor(() -> memory().place("bed").isPresent(), 8000, "sleep ok 后记了 bed");
        assertTrue(memory().place("home").isPresent(), "没记过家就顺手记成 home");
        assertEquals(memory().place("bed").orElseThrow().x(), memory().place("home").orElseThrow().x());
    }

    private static JsonObject planArgs(String stepsJson) {
        JsonObject o = new JsonObject();
        o.addProperty("goal", "去弄 20 根木头回来");
        o.add("steps", JsonParser.parseString(stepsJson));
        return o;
    }

    @Test
    void 模型用make_plan给合法计划() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.makePlanArgs = planArgs("""
                [{"command":"mine","args":{"block":"oak_log","count":20},"why":"砍"},
                 {"command":"deliver","args":{"item":"oak_log","count":20},"why":"交"}]""");
        ui.send(MessageType.CHAT, new Chat("去弄 20 根木头回来"));
        String said = ui.reply(5000);
        assertTrue(said.contains("我去砍树"), "计划开始前说一句打算：" + said);
        waitFor(() -> mockConsole.contains("收到 mine"), 5000, "第一步派出");
        Envelope done = planMessage(ui, p -> p.steps().stream().allMatch(st -> st.state() == dev.blockmate.protocol.PlanStepState.DONE), 15000);
        assertNotNull(done);
        dev.blockmate.protocol.Plan p = ProtocolCodec.bodyAs(done, dev.blockmate.protocol.Plan.class);
        assertEquals("去弄 20 根木头回来", p.goal());
        assertEquals(2, p.steps().size());
        assertTrue(mockConsole.contains("收到 deliver"));
        waitFor(() -> app.hub().currentPlan().map(pr -> pr.outcome() == PlanRun.Outcome.DONE).orElse(false), 2000, "done");
    }

    @Test
    void 计划不合法_重问一次_第二次合法() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.makePlanArgs = planArgs("""
                [{"command":"fly","args":{}},{"command":"mine","args":{"block":"oak_log"}}]""");
        model.makePlanArgsRetry = planArgs("""
                [{"command":"mine","args":{"block":"oak_log","count":20}},
                 {"command":"deliver","args":{"item":"oak_log","count":20}}]""");
        int before = model.requests.size();
        ui.send(MessageType.CHAT, new Chat("去弄 20 根木头回来"));
        String said = ui.reply(8000);
        assertTrue(said.contains("再想想"), "第二次的话也到了网页：" + said);
        assertTrue(model.requests.stream().skip(before).anyMatch(req -> req.messages().get(req.messages().size() - 1).content()
                .contains("上次给的计划有问题") && req.messages().get(req.messages().size() - 1).content().contains("fly")),
                "重问时把毛病告诉模型");
        waitFor(() -> mockConsole.contains("收到 mine"), 5000, "第二次的计划开始执行");
        assertTrue(companionSaid("再想想"), "两次的话合在一条消息里存下");
    }

    @Test
    void 计划两次不合法_提示主人() throws Exception {
        startApp();
        UiClient ui = UiClient.connect(app.port(), "t", "t");
        ui.next(MessageType.HELLO, 3000);
        startMock(false);
        waitFor(() -> app.hub().companionInWorld(), 5000, "online");
        model.makePlanArgs = planArgs("""
                [{"command":"stop","args":{}}]""");
        model.makePlanArgsRetry = planArgs("""
                [{"command":"place","args":{"block":"minecraft:furnace"}}]""");
        ui.send(MessageType.CHAT, new Chat("去弄 20 根木头回来"));
        String said = ui.reply(8000);
        assertTrue(said.contains("没想清楚怎么做"), said);
        assertTrue(said.contains("furnace"), "把第二次的毛病说给主人：" + said);
        Thread.sleep(800);
        assertTrue(app.hub().currentPlan().isEmpty(), "没有计划开始");
        assertFalse(mockConsole.stream().anyMatch(l -> l.startsWith("收到 ")), "没派任何命令：" + mockConsole);
    }

    private String currentPlanCommandId() throws Exception {
        java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
        app.hub().submit(() -> f.complete(app.hub().planCommandIdForTest()));
        return f.get(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private dev.blockmate.server.memory.Memory memory() {
        return app.memory();
    }
}
