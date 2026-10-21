package dev.blockmate.keeper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeeperConfigTest {

    @Test
    void 默认值() {
        KeeperConfig c = KeeperConfig.fromMap(Map.of());
        assertEquals("ws://mini:8787/ws/keeper", c.hubUrl().toString());
        assertEquals("companion-vanilla-1.20.1", c.processPattern());
        assertEquals(10, c.checkIntervalS());
        assertEquals(3, c.maxRestartsPer10Min());
        assertEquals(15, c.stopGraceS());
        assertEquals(60, c.startupGraceS());
        assertEquals(List.of(), c.launchCmd());
    }

    @Test
    void 读取全部字段() {
        KeeperConfig c = KeeperConfig.fromMap(Map.of(
                "hub_url", "ws://127.0.0.1:8787/ws/keeper", "token", "t",
                "launch_cmd", List.of("caffeinate", "-ims", "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"),
                "process_pattern", "xyz", "check_interval_s", 5, "max_restarts_per_10min", 2, "stop_grace_s", 7));
        assertEquals("t", c.token());
        assertEquals(3, c.launchCmd().size());
        assertEquals("xyz", c.processPattern());
        assertEquals(5, c.checkIntervalS());
        assertEquals(2, c.maxRestartsPer10Min());
        assertEquals(7, c.stopGraceS());
    }
}
