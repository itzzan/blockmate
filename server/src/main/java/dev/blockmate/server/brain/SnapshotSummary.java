package dev.blockmate.server.brain;

import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 把 snapshot 摘要成一句中文给模型看（细则第 9 节）。 */
public final class SnapshotSummary {

    private SnapshotSummary() {
    }

    public static String describe(Snapshot s, boolean executorOnline, ItemNames names) {
        if (!executorOnline || s == null) {
            return "你现在不在游戏里。";
        }
        if (!s.inWorld()) {
            return "你的游戏客户端开着，但还没进入服务器的世界。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你在").append(dimName(s.dim()));
        if (s.pos() != null) {
            sb.append(String.format(" (%d, %d, %d)", Math.round(s.pos().x()), Math.round(s.pos().y()),
                    Math.round(s.pos().z())));
        }
        if (s.health() != null) {
            sb.append("，生命 ").append(Math.round(s.health())).append("/20");
        }
        if (s.food() != null) {
            sb.append("，饥饿 ").append(s.food()).append("/20");
        }
        sb.append("，背包：").append(inventory(s.inventory(), names)).append("。");
        if (s.owner() == null || !s.owner().visible()) {
            sb.append("没看到主人。");
        } else if (s.owner().distance() != null) {
            sb.append("主人在 ").append(Math.round(s.owner().distance())).append(" 格外。");
        } else {
            sb.append("主人在附近。");
        }
        if (s.task() != null && s.task().name() != null) {
            sb.append("当前任务：").append(taskName(s.task().name())).append("。");
        } else {
            sb.append("现在没有任务。");
        }
        return sb.toString();
    }

    static String inventory(Map<String, Integer> inv, ItemNames names) {
        if (inv == null || inv.isEmpty()) {
            return "空";
        }
        List<String> parts = new ArrayList<>();
        inv.forEach((id, n) -> {
            if (n != null && n > 0) {
                parts.add(names.name(id) + " " + n);
            }
        });
        return parts.isEmpty() ? "空" : String.join("、", parts);
    }

    public static String dimName(String dim) {
        if (dim == null) {
            return "不知道哪个世界";
        }
        return switch (dim) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> dim;
        };
    }

    public static String taskName(CommandName c) {
        return switch (c) {
            case FOLLOW -> "跟随中";
            case COME_HERE -> "正在过去";
            case WAIT_HERE -> "原地等待";
            case PICK_UP -> "正在捡东西";
            case DELIVER -> "正在交付";
            case REJOIN -> "正在重连";
            case REPORT_STATUS -> "查看状态";
            case STOP -> "正在停下";
            case CANCEL -> "取消中";
        };
    }
}
