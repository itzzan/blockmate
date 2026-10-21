package dev.blockmate.keeper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 找进程、起进程、停进程（macOS）。 */
public final class ProcessOps {

    private final Path launchLog;

    public ProcessOps(Path launchLog) {
        this.launchLog = launchLog;
    }

    /** 用 pgrep -f 找游戏的 Java 进程：命令行里既有模式串又有 java 的那个（caffeinate / prismlauncher 的命令行也含模式串，要排除）。 */
    public Optional<Long> findGamePid(String pattern) {
        List<String> lines = run(List.of("pgrep", "-fl", pattern));
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length < 2) {
                continue;
            }
            String cmd = parts[1];
            if (cmd.contains("bin/java") || cmd.startsWith("java ")) {
                try {
                    return Optional.of(Long.parseLong(parts[0]));
                } catch (NumberFormatException ignored) {
                    // 跳过
                }
            }
        }
        return Optional.empty();
    }

    /** 执行启动命令。这条命令本身可能立刻退出（Prism 已开时会转发请求），所以不盯它。 */
    public void launch(List<String> cmd) throws IOException {
        if (cmd.isEmpty()) {
            throw new IOException("launch_cmd 为空");
        }
        Files.createDirectories(launchLog.toAbsolutePath().getParent());
        new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog.toFile()))
                .start();
    }

    /** SIGTERM，等 graceS 秒没退就 SIGKILL。返回是否已经退出。 */
    public boolean stop(long pid, int graceS, String pattern) {
        run(List.of("kill", "-TERM", Long.toString(pid)));
        long deadline = System.currentTimeMillis() + graceS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!alive(pid)) {
                return true;
            }
            sleep(500);
        }
        Log.warn("pid " + pid + " 没在 " + graceS + " 秒内退出，SIGKILL");
        run(List.of("kill", "-KILL", Long.toString(pid)));
        sleep(500);
        return !alive(pid);
    }

    public boolean alive(long pid) {
        return !run(List.of("kill", "-0", Long.toString(pid)), true).isEmpty();
    }

    private static List<String> run(List<String> cmd) {
        return run(cmd, false);
    }

    /** 跑一条命令收集 stdout 行；exitOnly=true 时只关心退出码（成功返回一行 "ok"）。 */
    private static List<String> run(List<String> cmd, boolean exitOnly) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                }
            }
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (exitOnly) {
                return done && p.exitValue() == 0 ? List.of("ok") : List.of();
            }
        } catch (IOException e) {
            Log.warn("命令失败 " + cmd + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
