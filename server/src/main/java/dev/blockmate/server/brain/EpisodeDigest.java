package dev.blockmate.server.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockmate.server.memory.StoredEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一天的事件汇总成模型看得懂的几行中文（2026-10-23）：之前日记提示里事件只有 "command / result" 这种类型名，
 * 模型看不出到底干了什么，写出来的日记只有聊天。这里按动作名统计次数和结果，再加死亡/重生/掉线/被踢的次数。
 */
public final class EpisodeDigest {

    private EpisodeDigest() {
    }

    public static String describe(List<StoredEvent> events) {
        Map<String, String> nameOf = new LinkedHashMap<>();   // command_id → 动作名
        Map<String, int[]> perAction = new LinkedHashMap<>(); // 动作名 → [下令, 成功, 失败, 取消]
        Map<String, Integer> misc = new LinkedHashMap<>();
        for (StoredEvent e : events) {
            JsonObject p = payload(e.payload());
            switch (e.kind()) {
                case "command" -> {
                    String name = p.has("name") ? p.get("name").getAsString() : "?";
                    if (e.commandId() != null) {
                        nameOf.put(e.commandId(), name);
                    }
                    perAction.computeIfAbsent(name, k -> new int[4])[0]++;
                }
                case "result" -> {
                    String name = nameOf.getOrDefault(e.commandId(), "?");
                    int[] c = perAction.computeIfAbsent(name, k -> new int[4]);
                    String status = p.has("status") ? p.get("status").getAsString() : "";
                    switch (status) {
                        case "ok" -> c[1]++;
                        case "failed" -> c[2]++;
                        case "cancelled" -> c[3]++;
                        default -> {
                        }
                    }
                }
                case "death", "respawn", "kicked", "disconnected", "executor_online", "executor_offline", "chat" ->
                        misc.merge(e.kind(), 1, Integer::sum);
                default -> {
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> en : perAction.entrySet()) {
            int[] c = en.getValue();
            sb.append("- ").append(actionName(en.getKey())).append("：主人让我做了 ").append(c[0]).append(" 次");
            if (c[1] > 0) {
                sb.append("，做成 ").append(c[1]).append(" 次");
            }
            if (c[2] > 0) {
                sb.append("，没做成 ").append(c[2]).append(" 次");
            }
            if (c[3] > 0) {
                sb.append("，中途换成别的事 ").append(c[3]).append(" 次");
            }
            sb.append('\n');
        }
        if (misc.getOrDefault("death", 0) > 0) {
            sb.append("- 我死了 ").append(misc.get("death")).append(" 次\n");
        }
        if (misc.getOrDefault("kicked", 0) > 0) {
            sb.append("- 被服务器踢出 ").append(misc.get("kicked")).append(" 次\n");
        }
        if (misc.getOrDefault("chat", 0) > 0) {
            sb.append("- 主人在游戏里跟我说了 ").append(misc.get("chat")).append(" 句话\n");
        }
        int sessions = misc.getOrDefault("executor_online", 0);
        if (sessions > 0) {
            sb.append("- 我进了 ").append(sessions).append(" 次游戏\n");
        }
        return sb.toString();
    }

    /** 日记里用的动作名（和状态条的"跟随中/正在捡东西"不同，这里要的是动作本身）。 */
    private static String actionName(String wire) {
        return switch (wire) {
            case "follow" -> "跟随";
            case "come_here" -> "过来";
            case "wait_here" -> "等在原地";
            case "stop" -> "停下";
            case "pick_up" -> "捡东西";
            case "deliver" -> "交付";
            case "report_status" -> "报状态";
            case "rejoin" -> "重连服务器";
            case "cancel" -> "取消";
            default -> wire;
        };
    }

    private static JsonObject payload(String raw) {
        try {
            return raw == null || raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
