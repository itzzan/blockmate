package dev.blockmate.server.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blockmate.protocol.CommandName;

import java.util.Map;
import java.util.Set;

/** 给模型的工具定义（细则第 9 节），Ollama / OpenAI 通用的 function 格式。 */
public final class Tools {

    public static final String REMEMBER = "remember_preference";

    /** 动作工具：会变成发给模组的 command。 */
    public static final Set<String> ACTIONS = Set.of("follow", "come_here", "wait_here", "stop", "pick_up", "deliver",
            "report_status");

    private Tools() {
    }

    public static boolean isAction(String name) {
        return name != null && ACTIONS.contains(name);
    }

    public static CommandName commandOf(String toolName) {
        for (CommandName c : CommandName.values()) {
            if (c.wire().equals(toolName)) {
                return c;
            }
        }
        return null;
    }

    /** 决策请求：全部工具。 */
    public static JsonArray all() {
        JsonArray a = new JsonArray();
        a.add(fn("follow", "开始跟随玩家，一直跟到玩家让你停下", Map.of()));
        a.add(fn("come_here", "走到玩家身边", Map.of()));
        a.add(fn("wait_here", "停下，在原地等着", Map.of()));
        a.add(fn("stop", "立刻停下正在做的事", Map.of()));
        a.add(fn("pick_up", "捡起附近地上的某种物品", Map.of(
                "item", prop("string", "物品的英文 id，例如 minecraft:oak_log"),
                "radius", prop("integer", "搜索半径（格），可不填，默认 8"))));
        a.add(fn("deliver", "把背包里的某种物品交给玩家", Map.of(
                "item", prop("string", "物品的英文 id，例如 minecraft:oak_log"),
                "count", prop("integer", "数量"),
                "to", prop("string", "交给谁的玩家名，可不填，默认是主人"))));
        a.add(fn("report_status", "看看自己现在在哪、有什么、在做什么", Map.of()));
        a.add(remember());
        return a;
    }

    /** "说一句话"请求：不带动作工具，防止它自己给自己下命令。 */
    public static JsonArray chatOnly() {
        JsonArray a = new JsonArray();
        a.add(remember());
        return a;
    }

    private static JsonObject remember() {
        return fn(REMEMBER, "玩家表达了一个长期的偏好或约定（比如几点以后不玩、不喜欢什么）时记下来；要玩家确认后才生效",
                Map.of("text", prop("string", "用一句话写下这个偏好")));
    }

    private static JsonObject prop(String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    private static JsonObject fn(String name, String description, Map<String, JsonObject> props) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        props.forEach((k, v) -> {
            properties.add(k, v);
            if (k.equals("item") || k.equals("count") || k.equals("text")) {
                required.add(k);
            }
        });
        params.add("properties", properties);
        if (!required.isEmpty()) {
            params.add("required", required);
        }
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", params);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }
}
