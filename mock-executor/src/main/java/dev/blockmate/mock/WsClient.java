package dev.blockmate.mock;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.ProtocolException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 用 JDK 自带的 WebSocket 客户端连后台。断了每 5 秒重连（手册 5.4 第 4 条）。
 * 所有对 Simulator 的调用都搬到 exec 这一个线程上，Simulator 本身不用考虑并发。
 */
public final class WsClient implements WebSocket.Listener, Simulator.Sink {

    private static final long RECONNECT_SECONDS = 5;

    private final URI uri;
    private final ScheduledExecutorService exec;
    private final Consumer<String> console;
    private final HttpClient http = HttpClient.newHttpClient();
    private final StringBuilder partial = new StringBuilder();

    private Simulator simulator;
    private WebSocket ws;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private int attempts;
    private volatile boolean stopped;

    public WsClient(URI hub, String token, ScheduledExecutorService exec, Consumer<String> console) {
        this.uri = withToken(hub, token);
        this.exec = exec;
        this.console = console;
    }

    public void attach(Simulator simulator) {
        this.simulator = simulator;
    }

    static URI withToken(URI hub, String token) {
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String base = hub.toString();
        return URI.create(base + (hub.getQuery() == null ? "?" : "&") + "token=" + encoded);
    }

    public void start() {
        exec.execute(this::connect);
    }

    /** 主动断开并不再重连（测试和退出时用）。 */
    public void stop() {
        stopped = true;
        exec.execute(() -> {
            WebSocket socket = ws;
            ws = null;
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            }
            simulator.onDisconnected();
        });
    }

    private void connect() {
        if (stopped) {
            return;
        }
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, this)
                .whenComplete((socket, error) -> exec.execute(() -> {
                    if (error != null) {
                        attempts++;
                        console.accept("重连中（第 " + attempts + " 次，" + RECONNECT_SECONDS + " 秒后再试）: " + root(error));
                        exec.schedule(this::connect, RECONNECT_SECONDS, TimeUnit.SECONDS);
                        return;
                    }
                    ws = socket;
                    attempts = 0;
                    console.accept("已连上 " + stripToken(uri));
                    simulator.onConnected(System.currentTimeMillis());
                }));
    }

    @Override
    public void send(Envelope envelope) {
        WebSocket socket = ws;
        if (socket == null) {
            return;
        }
        String text = ProtocolCodec.encode(envelope);
        // JDK 的 WebSocket 不允许上一条还没发完就发下一条，所以串成一条链。
        sendChain = sendChain.thenCompose(v -> socket.sendText(text, true)).exceptionally(err -> {
            console.accept("发送失败: " + root(err));
            return null;
        });
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String message = partial.toString();
            partial.setLength(0);
            exec.execute(() -> handle(message));
        }
        socket.request(1);
        return null;
    }

    private void handle(String message) {
        Envelope envelope;
        try {
            envelope = ProtocolCodec.decode(message);
        } catch (ProtocolException e) {
            console.accept("收到不合法的消息，忽略: " + e.getMessage());
            return;
        }
        simulator.onMessage(envelope, System.currentTimeMillis());
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
        exec.execute(() -> lost("后台关闭了连接 " + statusCode + (reason.isEmpty() ? "" : " " + reason)));
        return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        exec.execute(() -> lost("连接出错: " + root(error)));
    }

    private void lost(String why) {
        if (ws == null || stopped) {
            return;
        }
        ws = null;
        console.accept(why);
        simulator.onDisconnected();
        exec.schedule(this::connect, RECONNECT_SECONDS, TimeUnit.SECONDS);
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : " " + c.getMessage());
    }

    private static String stripToken(URI u) {
        return u.toString().replaceAll("token=[^&]*", "token=***");
    }
}
