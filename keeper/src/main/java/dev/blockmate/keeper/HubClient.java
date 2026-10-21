package dev.blockmate.keeper;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Hello;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Peer;
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

/** 连后台的 /ws/keeper，断了退避重连（2/4/8/…/30 秒）。回调搬到 exec 线程。 */
public final class HubClient implements WebSocket.Listener {

    private final URI uri;
    private final String token;
    private final ScheduledExecutorService exec;
    private final Consumer<Envelope> onMessage;
    private final Runnable onConnected;
    private final HttpClient http = HttpClient.newHttpClient();
    private final StringBuilder partial = new StringBuilder();
    private WebSocket ws;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private int attempts;

    public HubClient(URI hub, String token, ScheduledExecutorService exec, Consumer<Envelope> onMessage,
                     Runnable onConnected) {
        this.uri = URI.create(hub + (hub.getQuery() == null ? "?" : "&") + "token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8));
        this.token = token;
        this.exec = exec;
        this.onMessage = onMessage;
        this.onConnected = onConnected;
    }

    public void start() {
        exec.execute(this::connect);
    }

    public boolean isOpen() {
        return ws != null;
    }

    private void connect() {
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri, this)
                .whenComplete((socket, error) -> exec.execute(() -> {
                    if (error != null) {
                        int delay = (int) Math.min(30, 2L << Math.min(attempts, 10));
                        attempts++;
                        Log.info("后台连不上，" + delay + " 秒后重试（第 " + attempts + " 次）");
                        exec.schedule(this::connect, delay, TimeUnit.SECONDS);
                        return;
                    }
                    ws = socket;
                    attempts = 0;
                    Log.info("已连上后台");
                    send(ProtocolCodec.envelope(MessageType.HELLO, Peer.KEEPER, Peer.HUB, Hello.keeper(token, Main.VERSION)));
                    onConnected.run();
                }));
    }

    public void send(Envelope e) {
        WebSocket socket = ws;
        if (socket == null) {
            return;
        }
        String text = ProtocolCodec.encode(e);
        sendChain = sendChain.thenCompose(v -> socket.sendText(text, true)).exceptionally(err -> null);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String msg = partial.toString();
            partial.setLength(0);
            exec.execute(() -> {
                try {
                    onMessage.accept(ProtocolCodec.decode(msg));
                } catch (ProtocolException e) {
                    Log.warn("收到不合法的消息: " + e.getMessage());
                }
            });
        }
        socket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
        exec.execute(() -> lost("后台关闭了连接 " + code));
        return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        exec.execute(() -> lost("连接出错: " + error));
    }

    private void lost(String why) {
        if (ws == null) {
            return;
        }
        ws = null;
        Log.info(why + "，5 秒后重连；期望状态保持不变");
        exec.schedule(this::connect, 5, TimeUnit.SECONDS);
    }
}
