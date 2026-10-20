package dev.blockmate.server;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Map;

/** config.yaml（细则第 12 节）。所有路径相对 config.yaml 所在目录。 */
public final class Config {

    public record Ollama(String baseUrl, String model, String api, int firstTokenTimeoutS, int totalTimeoutS) {
    }

    public record Timeouts(int ackS, int comeHereS, int pickUpS, int deliverS, int stopS, int rejoinS) {
    }

    public final Path baseDir;
    public final int port;
    public final String token;
    public final String ownerName;
    public final String companionName;
    public final Path personaPath;
    public final Path dataDir;
    public final int historyN;
    public final Ollama ollama;
    public final Timeouts timeouts;
    public final LocalTime episodeTime;
    public final LocalTime backupTime;
    public final String logLevel;

    private Config(Path baseDir, Map<String, Object> m) {
        this.baseDir = baseDir;
        this.port = intOf(m, "port", 8787);
        this.token = str(m, "token", "CHANGE_ME");
        this.ownerName = str(m, "owner_name", "Steve");
        this.companionName = str(m, "companion_name", "Blockmate");
        this.personaPath = baseDir.resolve(str(m, "persona_path", "persona.md"));
        this.dataDir = baseDir.resolve(str(m, "data_dir", "data"));
        this.historyN = intOf(m, "history_n", 20);
        Map<String, Object> o = sub(m, "ollama");
        this.ollama = new Ollama(str(o, "base_url", "http://127.0.0.1:11434"), str(o, "model", "companion-local"),
                str(o, "api", "native"), intOf(o, "first_token_timeout_s", 30), intOf(o, "total_timeout_s", 60));
        Map<String, Object> t = sub(m, "timeouts_s");
        this.timeouts = new Timeouts(intOf(t, "ack", 3), intOf(t, "come_here", 90), intOf(t, "pick_up", 45),
                intOf(t, "deliver", 90), intOf(t, "stop", 5), intOf(t, "rejoin", 60));
        this.episodeTime = LocalTime.parse(str(m, "episode_time", "23:55"));
        this.backupTime = LocalTime.parse(str(m, "backup_time", "03:30"));
        this.logLevel = str(m, "log_level", "INFO");
    }

    public static Config load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(r);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            Path base = file.toAbsolutePath().getParent();
            return new Config(base, m);
        }
    }

    /** 测试用：给定目录和覆盖项。 */
    public static Config of(Path baseDir, Map<String, Object> overrides) {
        return new Config(baseDir, overrides);
    }

    public Path logDir() {
        return baseDir.resolve("logs");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? def : Integer.parseInt(String.valueOf(v));
    }
}
