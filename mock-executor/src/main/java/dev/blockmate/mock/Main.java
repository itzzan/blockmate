package dev.blockmate.mock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 入口：解析参数，起一个单线程调度器，连后台，每 200 ms 让 Simulator tick 一次。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (IllegalArgumentException e) {
            if (!"help".equals(e.getMessage())) {
                System.err.println("参数错误: " + e.getMessage());
            }
            System.err.print(Options.USAGE);
            System.exit(2);
            return;
        }

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mock-executor");
            t.setDaemon(false);
            return t;
        });
        WsClient client = new WsClient(opts.hub(), opts.token(), exec, Main::log);
        Simulator simulator = new Simulator(opts, client, Main::log);
        client.attach(simulator);

        log("假执行器启动：伙伴=" + opts.player() + " 主人=" + opts.owner() + " 后台=" + opts.hub()
                + (opts.failStuck() ? " [--fail stuck]" : "") + (opts.silent() ? " [--silent]" : ""));
        client.start();
        exec.scheduleAtFixedRate(() -> simulator.tick(System.currentTimeMillis()), 200, 200, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("退出");
            exec.shutdownNow();
        }));
    }

    /** "收到 xxx" 按手册原样打印；其余加前缀和时间。 */
    private static void log(String line) {
        if (line.startsWith("收到 ")) {
            System.out.println(line);
        } else {
            System.out.println("[mock " + java.time.LocalTime.now().withNano(0) + "] " + line);
        }
        System.out.flush();
    }
}
