package dev.blockmate.server;

import java.nio.file.Files;
import java.nio.file.Path;

/** 入口：java -jar server.jar --config config.yaml */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("config.yaml");
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = Path.of(args[++i]);
            } else if ("-h".equals(args[i]) || "--help".equals(args[i])) {
                System.err.println("用法：java -jar server.jar --config config.yaml");
                System.exit(2);
            }
        }
        if (!Files.exists(configPath)) {
            System.err.println("找不到配置文件 " + configPath.toAbsolutePath() + "，先把 config.example.yaml 拷成 config.yaml 填好");
            System.exit(2);
        }
        Config cfg = Config.load(configPath);
        // logback 在第一次取 logger 时初始化，所以日志目录必须在这之前设好
        Files.createDirectories(cfg.logDir());
        System.setProperty("blockmate.logdir", cfg.logDir().toString());
        System.setProperty("blockmate.loglevel", cfg.logLevel);

        ServerApp app = ServerApp.start(cfg, cfg.port);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "shutdown"));
    }
}
