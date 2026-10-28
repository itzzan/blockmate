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

    /** planS 是整个多步计划的总时长（任务单 10，默认 15 分钟）。 */
    public record Timeouts(int ackS, int comeHereS, int pickUpS, int deliverS, int stopS, int rejoinS, int planS, int fightS) {

        /** 任务单 20 之前的写法（没有 fight 的超时）。 */
        public Timeouts(int ackS, int comeHereS, int pickUpS, int deliverS, int stopS, int rejoinS, int planS) {
            this(ackS, comeHereS, pickUpS, deliverS, stopS, rejoinS, planS, 60);
        }
    }

    /** 服务器的 RCON（手册 7.2 可选的自动上下线用）。密码优先从 passwordFile 读（一行），否则用 password。 */
    public record Rcon(String host, int port, Path passwordFile, String password) {
    }

    /** 自动上下线：每 pollS 秒查一次谁在线；主人离开 offlineAfterMin 分钟后让伙伴下线。开关本身存在设置表里。 */
    /** 任务单 18：空闲循环——没人说话时每 intervalS 秒想一次要不要主动说点什么；网页上的开关存在设置表里，优先于这里的 enabled。 */
    public record Idle(boolean enabled, int intervalS) {
    }

    /** 任务单 19：战斗开关 off / defend / protect；任务单 20 起默认 protect；网页上的下拉存在设置表里，优先于这里。 */
    public record Combat(String mode) {
    }

    public record AutoPresence(int pollS, int offlineAfterMin) {
    }

    public final Path baseDir;
    public final int port;
    public final String token;
    public final String ownerName;
    public final String companionName;
    public final Path personaPath;
    /** 知识层目录（任务单 11）：plan_examples.md 等，相对 config.yaml 所在目录。 */
    public final Path knowledgeDir;
    public final Path dataDir;
    public final int historyN;
    public final Ollama ollama;
    public final Timeouts timeouts;
    public final LocalTime episodeTime;
    public final LocalTime backupTime;
    public final String logLevel;
    /** config.yaml 没有 rcon 一段时为 null，自动上下线不可用。 */
    public final Rcon rcon;
    public final AutoPresence autoPresence;
    public final Idle idle;
    public final Combat combat;

    private Config(Path baseDir, Map<String, Object> m) {
        this.baseDir = baseDir;
        this.port = intOf(m, "port", 8787);
        this.token = str(m, "token", "CHANGE_ME");
        this.ownerName = str(m, "owner_name", "Steve");
        this.companionName = str(m, "companion_name", "Blockmate");
        this.personaPath = baseDir.resolve(str(m, "persona_path", "persona.md"));
        this.knowledgeDir = baseDir.resolve(str(m, "knowledge_dir", "knowledge"));
        this.dataDir = baseDir.resolve(str(m, "data_dir", "data"));
        this.historyN = intOf(m, "history_n", 20);
        Map<String, Object> o = sub(m, "ollama");
        this.ollama = new Ollama(str(o, "base_url", "http://127.0.0.1:11434"), str(o, "model", "companion-local"),
                str(o, "api", "native"), intOf(o, "first_token_timeout_s", 30), intOf(o, "total_timeout_s", 60));
        Map<String, Object> t = sub(m, "timeouts_s");
        this.timeouts = new Timeouts(intOf(t, "ack", 3), intOf(t, "come_here", 90), intOf(t, "pick_up", 45),
                intOf(t, "deliver", 90), intOf(t, "stop", 5), intOf(t, "rejoin", 60), intOf(t, "plan", 900), intOf(t, "fight", 60));
        this.episodeTime = LocalTime.parse(str(m, "episode_time", "23:55"));
        this.backupTime = LocalTime.parse(str(m, "backup_time", "03:30"));
        this.logLevel = str(m, "log_level", "INFO");
        Map<String, Object> r = sub(m, "rcon");
        if (r.isEmpty()) {
            this.rcon = null;
        } else {
            String pf = str(r, "password_file", "");
            this.rcon = new Rcon(str(r, "host", "127.0.0.1"), intOf(r, "port", 25575),
                    pf.isBlank() ? null : baseDir.resolve(pf), str(r, "password", ""));
        }
        Map<String, Object> a = sub(m, "auto_presence");
        this.autoPresence = new AutoPresence(intOf(a, "poll_s", 30), intOf(a, "offline_after_min", 10));
        Map<String, Object> i = sub(m, "idle");
        Object en = i.get("enabled");
        this.idle = new Idle(en == null || Boolean.parseBoolean(String.valueOf(en)), intOf(i, "interval_s", 60));
        this.combat = new Combat(str(sub(m, "combat"), "mode", "protect")); // 任务单 20：主人定默认"保护你"
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
