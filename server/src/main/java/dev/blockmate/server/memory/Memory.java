package dev.blockmate.server.memory;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * SQLite 记忆（细则第 11 节）：messages / events / preferences / episodes，FTS5 全文索引，备份。
 * 单线程使用（hub 线程）。
 */
public final class Memory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Memory.class);

    private final Connection conn;

    public Memory(Path dbFile) {
        try {
            Files.createDirectories(dbFile.toAbsolutePath().getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            schema();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("打不开数据库 " + dbFile + ": " + e.getMessage(), e);
        }
    }

    private void schema() throws SQLException {
        String[] ddl = {
            """
            CREATE TABLE IF NOT EXISTS messages (
              id TEXT PRIMARY KEY, ts INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL,
              reply_to TEXT, tool_calls TEXT, deleted INTEGER NOT NULL DEFAULT 0)""",
            "CREATE INDEX IF NOT EXISTS messages_ts ON messages(ts)",
            // trigram 分词：中文也能按子串搜（至少 3 个字）
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
              text, content='messages', content_rowid='rowid', tokenize='trigram')""",
            """
            CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
              INSERT INTO messages_fts(rowid, text) VALUES (new.rowid, new.text); END""",
            """
            CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
              INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.rowid, old.text); END""",
            """
            CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
              INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
              INSERT INTO messages_fts(rowid, text) VALUES (new.rowid, new.text); END""",
            """
            CREATE TABLE IF NOT EXISTS events (
              id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, command_id TEXT,
              source_message_id TEXT, payload TEXT NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS events_ts ON events(ts)",
            """
            CREATE TABLE IF NOT EXISTS preferences (
              id TEXT PRIMARY KEY, ts INTEGER NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL,
              source_message_id TEXT, decided_ts INTEGER)""",
            """
            CREATE TABLE IF NOT EXISTS episodes (
              id TEXT PRIMARY KEY, day TEXT NOT NULL UNIQUE, summary TEXT NOT NULL,
              from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL, created_ts INTEGER NOT NULL)""",
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(
              summary, content='episodes', content_rowid='rowid', tokenize='trigram')""",
            """
            CREATE TRIGGER IF NOT EXISTS episodes_ai AFTER INSERT ON episodes BEGIN
              INSERT INTO episodes_fts(rowid, summary) VALUES (new.rowid, new.summary); END""",
            """
            CREATE TRIGGER IF NOT EXISTS episodes_ad AFTER DELETE ON episodes BEGIN
              INSERT INTO episodes_fts(episodes_fts, rowid, summary) VALUES ('delete', old.rowid, old.summary); END""",
            """
            CREATE TRIGGER IF NOT EXISTS episodes_au AFTER UPDATE ON episodes BEGIN
              INSERT INTO episodes_fts(episodes_fts, rowid, summary) VALUES ('delete', old.rowid, old.summary);
              INSERT INTO episodes_fts(rowid, summary) VALUES (new.rowid, new.summary); END""",
            // 细则之外的小表：keeper 的期望状态、日记是否需要重算等
            "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            // 任务单 10：计划（多步任务）的记录，结束时写一行；给第三阶段的"学习"留材料
            """
            CREATE TABLE IF NOT EXISTS plans (
              id TEXT PRIMARY KEY, goal TEXT NOT NULL, steps_json TEXT NOT NULL, outcome TEXT NOT NULL,
              started_ts INTEGER NOT NULL, ended_ts INTEGER NOT NULL, source_message_id TEXT)""",
            "CREATE INDEX IF NOT EXISTS plans_ended ON plans(ended_ts)",
            // 任务单 13：记过的地点（home / bed / 主人起的名字），hub 是唯一的存处
            """
            CREATE TABLE IF NOT EXISTS places (
              name TEXT PRIMARY KEY, dim TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
              ts INTEGER NOT NULL)""",
        };
        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
    }

    // ---------- places（任务单 13） ----------

    /** 记地点：同名覆盖。 */
    public void savePlace(String name, String dim, double x, double y, double z) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO places(name, dim, x, y, z, ts) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(name) DO UPDATE SET dim=excluded.dim, x=excluded.x, y=excluded.y, z=excluded.z, ts=excluded.ts")) {
            ps.setString(1, name);
            ps.setString(2, dim);
            ps.setDouble(3, x);
            ps.setDouble(4, y);
            ps.setDouble(5, z);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    public Optional<StoredPlace> place(String name) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, dim, x, y, z, ts FROM places WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new StoredPlace(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getLong(6))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    public List<StoredPlace> places() {
        List<StoredPlace> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, dim, x, y, z, ts FROM places ORDER BY ts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new StoredPlace(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                        rs.getLong(6)));
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return out;
    }

    // ---------- messages ----------

    public StoredMessage saveMessage(String role, String text, String replyTo, String toolCallsJson) {
        StoredMessage m = new StoredMessage(UUID.randomUUID().toString(), System.currentTimeMillis(), role, text,
                replyTo, toolCallsJson);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO messages(id, ts, role, text, reply_to, tool_calls) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, m.id());
            ps.setLong(2, m.ts());
            ps.setString(3, m.role());
            ps.setString(4, m.text());
            ps.setString(5, m.replyTo());
            ps.setString(6, m.toolCalls());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
        return m;
    }

    /** 最近 n 条未删除的消息，按时间从早到晚。 */
    public List<StoredMessage> recentMessages(int n) {
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, ts, role, text, reply_to, tool_calls FROM messages WHERE deleted=0 ORDER BY ts DESC, rowid DESC LIMIT ?")) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        Collections.reverse(out);
        return out;
    }

    public Optional<StoredMessage> message(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, ts, role, text, reply_to, tool_calls FROM messages WHERE id=? AND deleted=0")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readMessage(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** 删除消息：正文清空、标记 deleted，连带删掉由它派生的 events 和 preferences，当天日记标记需重算。 */
    public void deleteMessage(String id) {
        Optional<StoredMessage> m = message(id);
        if (m.isEmpty()) {
            return;
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE messages SET deleted=1, text='', tool_calls=NULL WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM events WHERE source_message_id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM preferences WHERE source_message_id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // ignore
            }
            throw wrap(e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // ignore
            }
        }
        setSetting("episode_stale:" + dayOf(m.get().ts()), "1");
    }

    /** 全文搜索（trigram，至少 3 个字）。 */
    public List<StoredMessage> searchMessages(String query, int limit) {
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT m.id, m.ts, m.role, m.text, m.reply_to, m.tool_calls FROM messages_fts f "
                        + "JOIN messages m ON m.rowid = f.rowid WHERE messages_fts MATCH ? AND m.deleted=0 "
                        + "ORDER BY m.ts DESC LIMIT ?")) {
            ps.setString(1, "\"" + query.replace("\"", "\"\"") + "\"");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return out;
    }

    private static StoredMessage readMessage(ResultSet rs) throws SQLException {
        return new StoredMessage(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6));
    }

    // ---------- events ----------

    public StoredEvent saveEvent(String kind, String commandId, String sourceMessageId, JsonObject payload) {
        StoredEvent e = new StoredEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), kind, commandId,
                sourceMessageId, payload == null ? "{}" : payload.toString());
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events(id, ts, kind, command_id, source_message_id, payload) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, e.id());
            ps.setLong(2, e.ts());
            ps.setString(3, e.kind());
            ps.setString(4, e.commandId());
            ps.setString(5, e.sourceMessageId());
            ps.setString(6, e.payload());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw wrap(ex);
        }
        return e;
    }

    public List<StoredEvent> eventsBetween(long fromTs, long toTs, Set<String> excludeKinds) {
        List<StoredEvent> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, ts, kind, command_id, source_message_id, payload FROM events WHERE ts>=? AND ts<? ORDER BY ts")) {
            ps.setLong(1, fromTs);
            ps.setLong(2, toTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(3);
                    if (excludeKinds != null && excludeKinds.contains(kind)) {
                        continue;
                    }
                    out.add(new StoredEvent(rs.getString(1), rs.getLong(2), kind, rs.getString(4), rs.getString(5),
                            rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return out;
    }

    public List<StoredMessage> messagesBetween(long fromTs, long toTs) {
        List<StoredMessage> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, ts, role, text, reply_to, tool_calls FROM messages WHERE deleted=0 AND ts>=? AND ts<? ORDER BY ts")) {
            ps.setLong(1, fromTs);
            ps.setLong(2, toTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readMessage(rs));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return out;
    }

    // ---------- preferences ----------

    public Preference addPendingPreference(String text, String sourceMessageId) {
        Preference p = new Preference(UUID.randomUUID().toString(), System.currentTimeMillis(), text, "pending",
                sourceMessageId, null);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO preferences(id, ts, text, status, source_message_id) VALUES (?,?,?,?,?)")) {
            ps.setString(1, p.id());
            ps.setLong(2, p.ts());
            ps.setString(3, p.text());
            ps.setString(4, p.status());
            ps.setString(5, p.sourceMessageId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
        return p;
    }

    public List<Preference> preferences(String status) {
        List<Preference> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, ts, text, status, source_message_id, decided_ts FROM preferences WHERE status=? ORDER BY ts")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long decided = rs.getLong(6);
                    out.add(new Preference(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.wasNull() ? null : decided));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return out;
    }

    /** confirm → active；ignore → ignored。只对 pending 的生效。 */
    public boolean decidePreference(String id, String action) {
        String status = switch (action) {
            case "confirm" -> "active";
            case "ignore" -> "ignored";
            default -> null;
        };
        if (status == null) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE preferences SET status=?, decided_ts=? WHERE id=? AND status='pending'")) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    // ---------- episodes ----------

    /** 同一天第二次写就覆盖。 */
    public Episode saveEpisode(String day, String summary, long fromTs, long toTs) {
        Episode e = new Episode(UUID.randomUUID().toString(), day, summary, fromTs, toTs, System.currentTimeMillis());
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO episodes(id, day, summary, from_ts, to_ts, created_ts) VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(day) DO UPDATE SET summary=excluded.summary, from_ts=excluded.from_ts, "
                        + "to_ts=excluded.to_ts, created_ts=excluded.created_ts")) {
            ps.setString(1, e.id());
            ps.setString(2, e.day());
            ps.setString(3, e.summary());
            ps.setLong(4, e.fromTs());
            ps.setLong(5, e.toTs());
            ps.setLong(6, e.createdTs());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw wrap(ex);
        }
        deleteSetting("episode_stale:" + day);
        return episodeForDay(day).orElse(e);
    }

    public Optional<Episode> episodeForDay(String day) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, day, summary, from_ts, to_ts, created_ts FROM episodes WHERE day=?")) {
            ps.setString(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readEpisode(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** 最近 n 天的日记，按日期从早到晚。 */
    // ---------- plans（任务单 10） ----------

    public StoredPlan savePlan(StoredPlan plan) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO plans(id, goal, steps_json, outcome, started_ts, ended_ts, source_message_id) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, plan.id());
            ps.setString(2, plan.goal());
            ps.setString(3, plan.stepsJson());
            ps.setString(4, plan.outcome());
            ps.setLong(5, plan.startedTs());
            ps.setLong(6, plan.endedTs());
            ps.setString(7, plan.sourceMessageId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
        return plan;
    }

    /** 最近 n 个计划，按结束时间从旧到新。 */
    public List<StoredPlan> recentPlans(int n) {
        List<StoredPlan> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, goal, steps_json, outcome, started_ts, ended_ts, source_message_id FROM plans "
                        + "ORDER BY ended_ts DESC LIMIT ?")) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredPlan(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6), rs.getString(7)));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        Collections.reverse(out);
        return out;
    }

    public List<Episode> recentEpisodes(int n) {
        List<Episode> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, day, summary, from_ts, to_ts, created_ts FROM episodes ORDER BY day DESC LIMIT ?")) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readEpisode(rs));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        Collections.reverse(out);
        return out;
    }

    private static Episode readEpisode(ResultSet rs) throws SQLException {
        return new Episode(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6));
    }

    // ---------- settings ----------

    public void setSetting(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO settings(key, value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    public Optional<String> setting(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    public void deleteSetting(String key) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM settings WHERE key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    // ---------- backup ----------

    /** VACUUM INTO dir/companion-YYYY-MM-DD.db，然后只保留最新 keep 份。 */
    public Path backup(Path dir, int keep) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve("companion-" + LocalDate.now() + ".db");
            Files.deleteIfExists(target);
            try (Statement st = conn.createStatement()) {
                st.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            prune(dir, keep);
            log.info("backup file={}", target.getFileName());
            return target;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("备份失败: " + e.getMessage(), e);
        }
    }

    static void prune(Path dir, int keep) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().matches("companion-\\d{4}-\\d{2}-\\d{2}\\.db"))
                    .sorted().toList();
        }
        for (int i = 0; i < files.size() - keep; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    public static String dayOf(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static IllegalStateException wrap(SQLException e) {
        return new IllegalStateException("数据库出错: " + e.getMessage(), e);
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("close: {}", e.getMessage());
        }
    }
}
