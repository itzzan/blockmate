package dev.blockmate.server.hub;

import dev.blockmate.server.gateway.RconClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 在自己的线程上每 pollS 秒用 RCON 查一次 `list`（开关打开时才查），结果交回 hub 线程。 */
public final class PresencePoller {

    private final Hub hub;
    private final RconClient rcon;

    public PresencePoller(Hub hub, RconClient rcon) {
        this.hub = hub;
        this.rcon = rcon;
    }

    public void start(ScheduledExecutorService exec, int pollS) {
        exec.scheduleWithFixedDelay(this::poll, 5, Math.max(5, pollS), TimeUnit.SECONDS);
    }

    void poll() {
        if (!hub.autoPresenceEnabled()) {
            return;
        }
        try {
            List<String> online = RconClient.parseList(rcon.command("list"));
            hub.submit(() -> hub.presenceObserved(online));
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            hub.submit(() -> hub.presenceFailed(why));
        }
    }
}
