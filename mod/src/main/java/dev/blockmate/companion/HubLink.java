package dev.blockmate.companion;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Hello;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Peer;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.ProtocolException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * link（手册 6.2）：连后台的 /ws/mc，断了按退避重连。
 * 回调都在 WebSocket 线程上，调用方自己搬到游戏线程。
 */
public final class HubLink {

    private final URI uri;
    private final String token;
    private final String player;
    private final String owner;
    private final int maxBackoffS;
    private final Consumer<Envelope> onMessage;
    private final Runnable onLost;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "companion-link");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean wanted;
    private volatile WebSocketClient client;
    private int attempts;

    public HubLink(String hubUrl, String token, String player, String owner, int maxBackoffS,
                   Consumer<Envelope> onMessage, Runnable onLost) {
        this.uri = withToken(URI.create(hubUrl), token);
        this.token = token;
        this.player = player;
        this.owner = owner;
        this.maxBackoffS = maxBackoffS;
        this.onMessage = onMessage;
        this.onLost = onLost;
    }

    static URI withToken(URI hub, String token) {
        String q = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return URI.create(hub.toString() + (hub.getQuery() == null ? "?" : "&") + q);
    }

    public void start() {
        wanted = true;
        attempts = 0;
        timer.execute(this::connectNow);
    }

    public void stop() {
        wanted = false;
        WebSocketClient c = client;
        client = null;
        if (c != null) {
            c.close();
        }
        timer.shutdownNow();
    }

    public boolean isOpen() {
        WebSocketClient c = client;
        return c != null && c.isOpen();
    }

    public void send(Envelope envelope) {
        WebSocketClient c = client;
        if (c != null && c.isOpen()) {
            c.send(ProtocolCodec.encode(envelope));
        }
    }

    public Envelope envelope(MessageType type, Object body) {
        return ProtocolCodec.envelope(type, Peer.MC, Peer.HUB, body);
    }

    private void connectNow() {
        if (!wanted) {
            return;
        }
        WebSocketClient c = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                attempts = 0;
                CompanionMod.LOG.info(CompanionMod.TAG + "hub connected");
                send(ProtocolCodec.encode(envelope(MessageType.HELLO, Hello.mc(token, player, owner, CompanionMod.VERSION))));
            }

            @Override
            public void onMessage(String message) {
                try {
                    onMessage.accept(ProtocolCodec.decode(message));
                } catch (ProtocolException e) {
                    CompanionMod.LOG.warn(CompanionMod.TAG + "bad message from hub: {}", e.getMessage());
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                CompanionMod.LOG.info(CompanionMod.TAG + "hub closed code={} remote={} reason={}", code, remote, reason);
                if (client == this) {
                    client = null;
                }
                // 手册 6.2 第 4 条：断开的瞬间立刻停下，不能没人管还在走
                onLost.run();
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                CompanionMod.LOG.warn(CompanionMod.TAG + "hub error: {}", ex.toString());
            }
        };
        c.setConnectionLostTimeout(30);
        client = c;
        c.connect();
    }

    private void scheduleReconnect() {
        if (!wanted) {
            return;
        }
        int delay = Backoff.delaySeconds(attempts++, maxBackoffS);
        CompanionMod.LOG.info(CompanionMod.TAG + "reconnect in {}s (attempt {})", delay, attempts);
        try {
            timer.schedule(this::connectNow, delay, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 已经 stop() 了
        }
    }
}
