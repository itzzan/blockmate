package dev.blockmate.server;

import dev.blockmate.server.brain.Brain;
import dev.blockmate.server.brain.ItemNames;
import dev.blockmate.server.brain.Persona;
import dev.blockmate.server.gateway.ModelClient;
import dev.blockmate.server.gateway.OllamaNativeClient;
import dev.blockmate.server.gateway.OllamaOpenAiClient;
import dev.blockmate.server.gateway.RconClient;
import dev.blockmate.server.hub.Hub;
import dev.blockmate.server.hub.PresencePoller;
import dev.blockmate.server.hub.WsEndpoints;
import dev.blockmate.server.memory.MaintenanceScheduler;
import dev.blockmate.server.memory.Memory;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 把四个模块装起来并启动 HTTP / WebSocket。测试里用 start(cfg, 0) 拿随机端口。 */
public final class ServerApp {

    private static final Logger log = LoggerFactory.getLogger(ServerApp.class);

    private final Javalin javalin;
    private final Hub hub;
    private final Memory memory;
    private final ScheduledExecutorService hubExec;
    private final ScheduledExecutorService brainExec;

    private ServerApp(Javalin javalin, Hub hub, Memory memory, ScheduledExecutorService hubExec,
                      ScheduledExecutorService brainExec) {
        this.javalin = javalin;
        this.hub = hub;
        this.memory = memory;
        this.hubExec = hubExec;
        this.brainExec = brainExec;
    }

    public static ServerApp start(Config cfg, int port) throws Exception {
        ModelClient client = switch (cfg.ollama.api()) {
            case "openai" -> new OllamaOpenAiClient(URI.create(cfg.ollama.baseUrl()),
                    Duration.ofSeconds(cfg.ollama.firstTokenTimeoutS()), Duration.ofSeconds(cfg.ollama.totalTimeoutS()));
            default -> new OllamaNativeClient(URI.create(cfg.ollama.baseUrl()),
                    Duration.ofSeconds(cfg.ollama.firstTokenTimeoutS()), Duration.ofSeconds(cfg.ollama.totalTimeoutS()));
        };
        return start(cfg, port, client);
    }

    public static ServerApp start(Config cfg, int port, ModelClient client) throws Exception {
        Files.createDirectories(cfg.dataDir);
        Memory memory = new Memory(cfg.dataDir.resolve("companion.db"));
        Persona persona = new Persona(cfg.personaPath, cfg.companionName, cfg.ownerName);
        ItemNames names = ItemNames.load();
        Brain brain = new Brain(client, cfg.ollama.model(), persona, names, cfg.ownerName, cfg.companionName);

        ScheduledExecutorService hubExec = Executors.newSingleThreadScheduledExecutor(r -> named(r, "hub"));
        ScheduledExecutorService brainExec = Executors.newSingleThreadScheduledExecutor(r -> named(r, "brain"));
        Hub hub = new Hub(cfg, memory, brain, names, hubExec, brainExec);

        Javalin javalin = Javalin.create(c -> {
            c.showJavalinBanner = false;
            c.staticFiles.add("/web", Location.CLASSPATH);
            c.jetty.modifyWebSocketServletFactory(f -> f.setIdleTimeout(Duration.ofSeconds(120)));
        });
        new WsEndpoints(cfg, hub, hubExec).register(javalin);
        javalin.start(port);

        hubExec.scheduleAtFixedRate(hub::tick, 500, 500, TimeUnit.MILLISECONDS);
        MaintenanceScheduler.schedule(hubExec, cfg.episodeTime, hub::writeDailyEpisode, "episode");
        hubExec.schedule(hub::catchUpEpisodes, 45, TimeUnit.SECONDS); // 启动后补写昨天的日记（失败/半截/删过消息时）
        MaintenanceScheduler.schedule(hubExec, cfg.backupTime, () -> hub.backup(cfg.dataDir.resolve("backup"), 14), "backup");
        if (cfg.rcon != null) {
            // 自动上下线（手册 7.2 可选项）：单独线程查 RCON，不占 hub 线程；密码每次从文件读，换了不用重启
            Config.Rcon rc = cfg.rcon;
            RconClient rcon = new RconClient(rc.host(), rc.port(), () -> {
                if (rc.passwordFile() != null) {
                    try {
                        return Files.readString(rc.passwordFile()).strip();
                    } catch (IOException e) {
                        return "";
                    }
                }
                return rc.password();
            });
            ScheduledExecutorService presenceExec = Executors.newSingleThreadScheduledExecutor(r -> named(r, "presence"));
            new PresencePoller(hub, rcon).start(presenceExec, cfg.autoPresence.pollS());
            log.info("auto_presence available rcon={}:{} poll_s={} offline_after_min={}", rc.host(), rc.port(),
                    cfg.autoPresence.pollS(), cfg.autoPresence.offlineAfterMin());
        }

        log.info("started port={} model={} api={} data={}", javalin.port(), cfg.ollama.model(), cfg.ollama.api(),
                cfg.dataDir);
        return new ServerApp(javalin, hub, memory, hubExec, brainExec);
    }

    public int port() {
        return javalin.port();
    }

    public Hub hub() {
        return hub;
    }

    public Memory memory() {
        return memory;
    }

    public void stop() {
        log.info("stopping");
        try {
            javalin.stop();
        } catch (RuntimeException e) {
            log.warn("javalin stop: {}", e.toString());
        }
        brainExec.shutdownNow();
        hubExec.shutdownNow();
        try {
            hubExec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        memory.close();
    }

    private static Thread named(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
