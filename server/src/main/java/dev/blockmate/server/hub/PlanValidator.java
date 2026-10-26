package dev.blockmate.server.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.blockmate.protocol.BlockRules;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.server.brain.Tools;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验模型给的计划（任务单 11）：命令在允许范围内、参数齐全合法、最多 8 步。不合法的整条拒掉，把原因列出来让模型重给。
 * 纯逻辑，可测试。
 */
public final class PlanValidator {

    public static final int MAX_STEPS = 8;

    public record Validated(String goal, List<PlanRun.Step> steps, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private PlanValidator() {
    }

    /** makePlanArgs = make_plan 工具的参数 {goal, steps:[{command,args,why}]}；rules 是 knowledge/blocks.yaml 的白名单（任务单 12）。 */
    public static Validated validate(JsonObject makePlanArgs, BlockRules rules) {
        List<String> errors = new ArrayList<>();
        String goal = makePlanArgs != null && makePlanArgs.has("goal") && !makePlanArgs.get("goal").isJsonNull()
                ? makePlanArgs.get("goal").getAsString().strip() : "";
        JsonElement stepsEl = makePlanArgs == null ? null : makePlanArgs.get("steps");
        if (stepsEl == null || !stepsEl.isJsonArray() || stepsEl.getAsJsonArray().isEmpty()) {
            errors.add("steps 为空");
            return new Validated(goal, List.of(), errors);
        }
        List<PlanRun.Step> steps = validateSteps(stepsEl.getAsJsonArray(), 0, errors, rules);
        return new Validated(goal, steps, errors);
    }

    /** 校验一组步骤（make_plan 的 steps 或 plan_decision 的 new_steps），index 从 firstIndex 起编。 */
    public static List<PlanRun.Step> validateSteps(JsonArray arr, int firstIndex, List<String> errors, BlockRules rules) {
        BlockRules r = rules == null ? BlockRules.empty() : rules;
        List<PlanRun.Step> out = new ArrayList<>();
        if (arr.size() > MAX_STEPS) {
            errors.add("步骤太多（" + arr.size() + " 步，最多 " + MAX_STEPS + " 步）");
            return out;
        }
        int idx = firstIndex;
        for (JsonElement el : arr) {
            int shown = idx - firstIndex + 1;
            if (!el.isJsonObject()) {
                errors.add("第 " + shown + " 步不是对象");
                idx++;
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String wire = o.has("command") && !o.get("command").isJsonNull() ? o.get("command").getAsString().strip() : "";
            CommandName name = CommandName.fromWire(wire);
            if (name == null || !Tools.PLANNABLE.contains(wire)) {
                errors.add("第 " + shown + " 步的命令「" + wire + "」不能用于计划");
                idx++;
                continue;
            }
            JsonObject args = o.has("args") && o.get("args").isJsonObject() ? o.getAsJsonObject("args") : new JsonObject();
            normalizeIds(args);
            String problem = checkArgs(name, args, r);
            if (problem != null) {
                errors.add("第 " + shown + " 步（" + wire + "）" + problem);
            }
            out.add(new PlanRun.Step(idx, name, args));
            idx++;
        }
        return out;
    }

    /** 物品 / 方块 id 没写命名空间就补 minecraft:。 */
    static void normalizeIds(JsonObject args) {
        for (String k : List.of("item", "block", "input", "fuel")) {
            if (args.has(k) && args.get(k).isJsonPrimitive()) {
                String v = args.get(k).getAsString().strip().toLowerCase();
                if (!v.isEmpty() && !v.contains(":")) {
                    v = "minecraft:" + v;
                }
                args.addProperty(k, v);
            }
        }
    }

    private static String checkArgs(CommandName name, JsonObject a, BlockRules rules) {
        return switch (name) {
            case MINE -> {
                String m = missing(a, "block");
                if (m != null) {
                    yield m;
                }
                String b = a.get("block").getAsString();
                if (!rules.mineable().contains(b)) {
                    yield b + " 不在允许挖的清单里";
                }
                yield positive(a, "count");
            }
            case DELIVER -> missing(a, "item") != null ? missing(a, "item") : positive(a, "count");
            case PICK_UP, CRAFT -> missing(a, "item");
            case USE_FURNACE -> missing(a, "input");
            case REMEMBER_PLACE -> missing(a, "name");
            case PLACE -> {
                String m = missing(a, "block");
                if (m != null) {
                    yield m;
                }
                String b = a.get("block").getAsString();
                yield rules.placeable().contains(b) ? null : "只允许放工作台、火把、床，不能放 " + b;
            }
            case GOTO -> {
                boolean pos = a.has("x") && a.has("y") && a.has("z");
                boolean place = a.has("place") && !a.get("place").isJsonNull() && !a.get("place").getAsString().isBlank();
                yield pos || place ? null : "要给坐标 x/y/z 或地点名 place";
            }
            default -> null;
        };
    }

    private static String missing(JsonObject a, String key) {
        return a.has(key) && !a.get(key).isJsonNull() && !a.get(key).getAsString().isBlank() ? null : "缺 " + key;
    }

    private static String positive(JsonObject a, String key) {
        if (!a.has(key) || a.get(key).isJsonNull()) {
            return "缺 " + key;
        }
        try {
            return a.get(key).getAsInt() >= 1 ? null : key + " 要大于 0";
        } catch (RuntimeException e) {
            return key + " 不是整数";
        }
    }
}
