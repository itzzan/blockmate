package dev.blockmate.mock;

import java.net.URI;

/** 命令行参数。 */
public record Options(URI hub, String token, String player, String owner, boolean failStuck, boolean silent,
                      long snapshotIntervalMs) {

    public static final String USAGE = """
            用法：java -jar mock-executor-all.jar [选项]
              --hub <ws 地址>     后台的 /ws/mc 地址，默认 ws://127.0.0.1:8787/ws/mc
              --token <口令>      config.yaml 里的 token，默认 CHANGE_ME
              --player <名字>     假装的伙伴玩家名，默认 Blockmate
              --owner <名字>      主人玩家名，默认 Steve
              --fail stuck        下一个任务 3 秒后返回 stuck（只生效一次）
              --silent            收到命令后什么都不回，用来测后台的超时
              --interval <毫秒>   snapshot 间隔，默认 1000
            """;

    public static Options parse(String[] args) {
        URI hub = URI.create("ws://127.0.0.1:8787/ws/mc");
        String token = "CHANGE_ME";
        String player = "Blockmate";
        String owner = "Steve";
        boolean failStuck = false;
        boolean silent = false;
        long interval = 1000;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--hub" -> hub = URI.create(need(args, ++i, a));
                case "--token" -> token = need(args, ++i, a);
                case "--player" -> player = need(args, ++i, a);
                case "--owner" -> owner = need(args, ++i, a);
                case "--interval" -> interval = Long.parseLong(need(args, ++i, a));
                case "--silent" -> silent = true;
                case "--fail" -> {
                    String mode = need(args, ++i, a);
                    if (!mode.equals("stuck")) {
                        throw new IllegalArgumentException("--fail 只支持 stuck，收到: " + mode);
                    }
                    failStuck = true;
                }
                case "-h", "--help" -> throw new IllegalArgumentException("help");
                default -> throw new IllegalArgumentException("不认识的参数: " + a);
            }
        }
        return new Options(hub, token, player, owner, failStuck, silent, interval);
    }

    private static String need(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " 后面要跟一个值");
        }
        return args[i];
    }
}
