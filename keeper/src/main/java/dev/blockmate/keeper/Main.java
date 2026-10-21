package dev.blockmate.keeper;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.HelloOk;
import dev.blockmate.protocol.KeeperCmd;
import dev.blockmate.protocol.KeeperStatus;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Peer;
import dev.blockmate.protocol.ProtocolCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** keeper 入口：java -jar keeper.jar --config keeper.yaml（手册 7.2，任务单 8）。 */
public final class Main {

    public static final String VERSION = "0.1.0";

    private final KeeperConfig cfg;
    private final KeeperPolicy policy;
    private final ProcessOps ops;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "keeper"));
    private HubClient hub;

    private Main(KeeperConfig cfg, Path baseDir) {
        this.cfg = cfg;
        this.policy = new KeeperPolicy(cfg.maxRestartsPer10Min(), cfg.startupGraceS() * 1000L);
        this.ops = new ProcessOps(baseDir.resolve("logs").resolve("game-launch.log"));
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("keeper.yaml");
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = Path.of(args[++i]);
            }
        }
        if (!Files.exists(configPath)) {
            System.err.println("找不到 " + configPath.toAbsolutePath() + "，先把 keeper.example.yaml 拷成 keeper.yaml 填好");
            System.exit(2);
        }
        KeeperConfig cfg = KeeperConfig.load(configPath);
        Main m = new Main(cfg, configPath.toAbsolutePath().getParent());
        m.run();
    }

    private void run() {
        Log.info("keeper 启动：后台=" + cfg.hubUrl() + " 进程特征=" + cfg.processPattern() + " 启动命令="
                + String.join(" ", cfg.launchCmd()));
        hub = new HubClient(cfg.hubUrl(), cfg.token(), exec, this::onMessage, this::check);
        hub.start();
        exec.scheduleAtFixedRate(this::check, cfg.checkIntervalS(), cfg.checkIntervalS(), TimeUnit.SECONDS);
    }

    private void onMessage(Envelope e) {
        switch (e.type()) {
            case HELLO -> {
                HelloOk ok = ProtocolCodec.bodyAs(e, HelloOk.class);
                if (!ok.ok()) {
                    Log.warn("后台拒绝了连接: " + ok.error());
                    return;
                }
                if ("running".equals(ok.desired())) {
                    policy.setDesired(KeeperPolicy.Desired.RUNNING);
                } else if ("stopped".equals(ok.desired())) {
                    policy.setDesired(KeeperPolicy.Desired.STOPPED);
                }
                Log.info("后台说期望状态是 " + policy.desired());
            }
            case KEEPER_CMD -> {
                KeeperCmd cmd = ProtocolCodec.bodyAs(e, KeeperCmd.class);
                if (KeeperCmd.START.equals(cmd.action())) {
                    policy.setDesired(KeeperPolicy.Desired.RUNNING);
                    Log.info("收到 start");
                } else if (KeeperCmd.STOP.equals(cmd.action())) {
                    policy.setDesired(KeeperPolicy.Desired.STOPPED);
                    Log.info("收到 stop");
                } else {
                    Log.warn("不认识的 keeper_cmd: " + cmd.action());
                    return;
                }
                check();
            }
            default -> {
                // 其他类型不关心
            }
        }
    }

    /** 每 10 秒一次（也在收到命令后立刻跑一次）：看进程、按策略动手、报状态。 */
    private void check() {
        long now = System.currentTimeMillis();
        Optional<Long> pid = ops.findGamePid(cfg.processPattern());
        KeeperPolicy.Action action = policy.decide(pid.isPresent(), now);
        switch (action) {
            case START -> {
                try {
                    Log.info("拉起游戏（10 分钟内第 " + policy.restartsInWindow(now) + " 次）");
                    ops.launch(cfg.launchCmd());
                    policy.onStarted(now);
                } catch (IOException e) {
                    Log.warn("启动失败: " + e.getMessage());
                }
            }
            case STOP -> {
                Log.info("关闭游戏 pid=" + pid.orElse(-1L));
                pid.ifPresent(p -> ops.stop(p, cfg.stopGraceS(), cfg.processPattern()));
            }
            default -> {
            }
        }
        if (policy.gaveUp() && action == KeeperPolicy.Action.NONE && pid.isEmpty()
                && policy.desired() == KeeperPolicy.Desired.RUNNING) {
            Log.warn("10 分钟内拉起 " + cfg.maxRestartsPer10Min() + " 次都没活下来，放弃；网页再点一次上线会重新开始");
        }
        Optional<Long> after = action == KeeperPolicy.Action.STOP ? ops.findGamePid(cfg.processPattern()) : pid;
        hub.send(ProtocolCodec.envelope(MessageType.KEEPER_STATUS, Peer.KEEPER, Peer.HUB, new KeeperStatus(
                after.isPresent(), after.map(Long::intValue).orElse(null),
                policy.desired() == KeeperPolicy.Desired.RUNNING ? "running" : "stopped",
                policy.lastStartMs() > 0 ? policy.lastStartMs() : null,
                policy.restartsInWindow(now), policy.gaveUp(), VERSION)));
    }
}
