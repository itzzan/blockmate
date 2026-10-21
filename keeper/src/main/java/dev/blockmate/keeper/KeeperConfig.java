package dev.blockmate.keeper;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** keeper.yaml（细则第 13 节）。 */
public record KeeperConfig(URI hubUrl, String token, List<String> launchCmd, String processPattern,
                           int checkIntervalS, int maxRestartsPer10Min, int stopGraceS, int startupGraceS) {

    public static KeeperConfig load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(r);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            return fromMap(m);
        }
    }

    @SuppressWarnings("unchecked")
    public static KeeperConfig fromMap(Map<String, Object> m) {
        Object cmd = m.get("launch_cmd");
        List<String> launch = cmd instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return new KeeperConfig(
                URI.create(str(m, "hub_url", "ws://mini:8787/ws/keeper")),
                str(m, "token", "CHANGE_ME"),
                launch,
                str(m, "process_pattern", "companion-vanilla-1.20.1"),
                intOf(m, "check_interval_s", 10),
                intOf(m, "max_restarts_per_10min", 3),
                intOf(m, "stop_grace_s", 15),
                intOf(m, "startup_grace_s", 60));
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? def : Integer.parseInt(String.valueOf(v));
    }
}
