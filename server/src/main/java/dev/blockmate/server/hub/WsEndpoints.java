package dev.blockmate.server.hub;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Hello;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Peer;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.ProtocolException;
import dev.blockmate.server.Config;
import io.javalin.Javalin;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 三个 WebSocket 端点和 token 校验（细则第 1 节）。
 * token 可以放查询参数 ?token=，也可以放首条 hello 的 body；3 秒内没有合法 hello 就 4401 断开。
 */
public final class WsEndpoints {

    private static final Logger log = LoggerFactory.getLogger(WsEndpoints.class);
    private static final int CLOSE_UNAUTHORIZED = 4401;

    private final Config cfg;
    private final Hub hub;
    private final ScheduledExecutorService exec;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();

    public WsEndpoints(Config cfg, Hub hub, ScheduledExecutorService exec) {
        this.cfg = cfg;
        this.hub = hub;
        this.exec = exec;
    }

    public void register(Javalin app) {
        app.ws("/ws/mc", ws -> wire(ws, Peer.MC));
        app.ws("/ws/ui", ws -> wire(ws, Peer.UI));
        app.ws("/ws/keeper", ws -> wire(ws, Peer.KEEPER));
    }

    private final class Conn implements Hub.Sender {
        final WsContext ctx;
        final Peer role;
        volatile boolean tokenOk;
        volatile boolean announced;
        volatile ScheduledFuture<?> helloTimer;

        Conn(WsContext ctx, Peer role) {
            this.ctx = ctx;
            this.role = role;
        }

        @Override
        public void send(Envelope envelope) {
            try {
                ctx.send(ProtocolCodec.encode(envelope));
            } catch (RuntimeException e) {
                throw new IllegalStateException("send failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void close(int code, String reason) {
            try {
                ctx.closeSession(code, reason);
            } catch (RuntimeException e) {
                log.debug("close failed: {}", e.toString());
            }
        }
    }

    private void wire(WsConfig ws, Peer role) {
        ws.onConnect(ctx -> {
            ctx.enableAutomaticPings();
            Conn c = new Conn(ctx, role);
            String q = ctx.queryParam("token");
            c.tokenOk = q != null && cfg.token.equals(q);
            conns.put(ctx.sessionId(), c);
            c.helloTimer = exec.schedule(() -> {
                if (!c.announced) {
                    log.warn("auth_timeout role={}", role.wire());
                    c.close(CLOSE_UNAUTHORIZED, "hello timeout");
                }
            }, 10, TimeUnit.SECONDS); // 10-23：Windows 浏览器首次打开偶发 3 秒内发不出 hello，放宽到 10 秒
        });
        ws.onMessage(ctx -> {
            Conn c = conns.get(ctx.sessionId());
            if (c == null) {
                return;
            }
            Envelope e;
            try {
                e = ProtocolCodec.decode(ctx.message());
            } catch (ProtocolException ex) {
                log.warn("bad_message role={} reason={}", role.wire(), ex.getMessage());
                return;
            }
            if (!c.announced) {
                if (e.type() != MessageType.HELLO) {
                    log.warn("auth_fail role={} reason=hello_first", role.wire());
                    c.close(CLOSE_UNAUTHORIZED, "hello first");
                    return;
                }
                Hello hello = ProtocolCodec.bodyAs(e, Hello.class);
                if (!c.tokenOk && !cfg.token.equals(hello.token())) {
                    log.warn("auth_fail role={} reason=bad_token", role.wire());
                    c.close(CLOSE_UNAUTHORIZED, "bad token");
                    return;
                }
                c.tokenOk = true;
                c.announced = true;
                if (c.helloTimer != null) {
                    c.helloTimer.cancel(false);
                }
                hub.submit(() -> announce(c, hello));
                return;
            }
            hub.submit(() -> dispatch(c, e));
        });
        ws.onClose(ctx -> {
            Conn c = conns.remove(ctx.sessionId());
            if (c != null) {
                if (c.helloTimer != null) {
                    c.helloTimer.cancel(false);
                }
                if (c.announced) {
                    hub.submit(() -> gone(c));
                }
            }
        });
        ws.onError(ctx -> log.debug("ws error role={}: {}", role.wire(), String.valueOf(ctx.error())));
    }

    private void announce(Conn c, Hello hello) {
        switch (c.role) {
            case MC -> hub.mcConnected(c, hello);
            case UI -> hub.uiConnected(c);
            case KEEPER -> hub.keeperConnected(c);
            default -> {
            }
        }
    }

    private void dispatch(Conn c, Envelope e) {
        switch (c.role) {
            case MC -> hub.mcMessage(e);
            case UI -> hub.uiMessage(c, e);
            case KEEPER -> hub.keeperMessage(e);
            default -> {
            }
        }
    }

    private void gone(Conn c) {
        switch (c.role) {
            case MC -> hub.mcDisconnected(c);
            case UI -> hub.uiDisconnected(c);
            case KEEPER -> hub.keeperDisconnected(c);
            default -> {
            }
        }
    }
}
