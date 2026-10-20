package dev.blockmate.server.memory;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 细则第 11 节：四张表、FTS、级联删除、备份保留 14 份。 */
class MemoryTest {

    @TempDir
    Path dir;
    Memory memory;

    @BeforeEach
    void open() {
        memory = new Memory(dir.resolve("data").resolve("companion.db"));
    }

    @AfterEach
    void close() {
        memory.close();
    }

    @Test
    void 消息按时间顺序取最近N条() throws InterruptedException {
        memory.saveMessage("owner", "一", null, null);
        Thread.sleep(2);
        memory.saveMessage("companion", "二", null, null);
        Thread.sleep(2);
        memory.saveMessage("owner", "三", null, null);
        List<StoredMessage> last2 = memory.recentMessages(2);
        assertEquals(List.of("二", "三"), last2.stream().map(StoredMessage::text).toList());
        assertEquals(3, memory.recentMessages(10).size());
    }

    @Test
    void 全文搜索中文子串() {
        memory.saveMessage("owner", "你跟我来一下", null, null);
        memory.saveMessage("owner", "今天天气不错", null, null);
        assertEquals(1, memory.searchMessages("跟我来", 10).size());
        assertEquals(0, memory.searchMessages("把木头", 10).size());
    }

    @Test
    void 删除消息连带派生条目() {
        StoredMessage m = memory.saveMessage("owner", "晚上十点以后别叫我", null, null);
        memory.saveEvent("command", "c1", m.id(), new JsonObject());
        memory.addPendingPreference("晚上十点以后不打游戏", m.id());
        memory.saveEvent("command", "c2", "other", new JsonObject());

        memory.deleteMessage(m.id());

        assertTrue(memory.message(m.id()).isEmpty());
        assertEquals(0, memory.preferences("pending").size());
        assertEquals(1, memory.eventsBetween(0, Long.MAX_VALUE, Set.of()).size());
        assertEquals(0, memory.searchMessages("十点以后", 10).size(), "删了的消息不该再被搜到");
        assertTrue(memory.setting("episode_stale:" + Memory.dayOf(m.ts())).isPresent());
    }

    @Test
    void 偏好要确认才生效() {
        Preference p = memory.addPendingPreference("别在我睡觉时说话", null);
        assertEquals(1, memory.preferences("pending").size());
        assertEquals(0, memory.preferences("active").size());
        assertTrue(memory.decidePreference(p.id(), "confirm"));
        assertEquals(1, memory.preferences("active").size());
        assertFalse(memory.decidePreference(p.id(), "confirm"), "已决定的不能再改");

        Preference q = memory.addPendingPreference("随便说的", null);
        assertTrue(memory.decidePreference(q.id(), "ignore"));
        assertEquals(1, memory.preferences("ignored").size());
        assertFalse(memory.decidePreference(q.id(), "dance"));
    }

    @Test
    void 同一天的日记覆盖_最近三条按日期() {
        memory.saveEpisode("2026-10-18", "第一天", 0, 1);
        memory.saveEpisode("2026-10-19", "第二天", 0, 1);
        memory.saveEpisode("2026-10-20", "第三天初稿", 0, 1);
        memory.saveEpisode("2026-10-20", "第三天终稿", 0, 1);
        memory.saveEpisode("2026-10-17", "更早", 0, 1);
        List<Episode> recent = memory.recentEpisodes(3);
        assertEquals(List.of("2026-10-18", "2026-10-19", "2026-10-20"), recent.stream().map(Episode::day).toList());
        assertEquals("第三天终稿", recent.get(2).summary());
    }

    @Test
    void 备份文件并只保留十四份() throws IOException {
        Path backupDir = dir.resolve("backup");
        Files.createDirectories(backupDir);
        for (int i = 1; i <= 16; i++) {
            Files.writeString(backupDir.resolve(String.format("companion-2026-09-%02d.db", i)), "x");
        }
        Files.writeString(backupDir.resolve("notes.txt"), "keep me");
        memory.saveMessage("owner", "备份前的一句", null, null);
        Path file = memory.backup(backupDir, 14);
        assertTrue(Files.exists(file));
        assertTrue(Files.size(file) > 0);
        long dbs;
        try (var s = Files.list(backupDir)) {
            dbs = s.filter(p -> p.getFileName().toString().endsWith(".db")).count();
        }
        assertEquals(14, dbs);
        assertTrue(Files.exists(backupDir.resolve("notes.txt")));
        assertFalse(Files.exists(backupDir.resolve("companion-2026-09-01.db")), "最旧的被删");
        try (Memory copy = new Memory(file)) {
            assertEquals(1, copy.recentMessages(10).size(), "备份里有数据");
        }
    }

    @Test
    void settings读写() {
        assertTrue(memory.setting("keeper_desired").isEmpty());
        memory.setSetting("keeper_desired", "running");
        memory.setSetting("keeper_desired", "stopped");
        assertEquals("stopped", memory.setting("keeper_desired").orElseThrow());
        memory.deleteSetting("keeper_desired");
        assertTrue(memory.setting("keeper_desired").isEmpty());
    }

    @Test
    void 每日任务的下次时间() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        assertEquals(13 * 3600_000L + 55 * 60_000L, MaintenanceScheduler.millisUntilNext(LocalTime.of(23, 55), now));
        assertEquals(17 * 3600_000L + 30 * 60_000L, MaintenanceScheduler.millisUntilNext(LocalTime.of(3, 30), now));
    }
}
