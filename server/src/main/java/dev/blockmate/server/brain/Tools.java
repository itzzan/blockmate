package dev.blockmate.server.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blockmate.protocol.CommandName;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 给模型的工具定义（细则第 9 节），Ollama / OpenAI 通用的 function 格式。 */
public final class Tools {

    public static final String REMEMBER = "remember_preference";

    public static final String MAKE_PLAN = "make_plan";
    public static final String PLAN_DECISION = "plan_decision";
    /** 任务单 17：主人问进度时调用，后台算出一句准确的话让模型照着说。 */
    public static final String REPORT_PROGRESS = "report_progress";
    /** 任务单 18：空闲循环"想一想"的三个工具。 */
    public static final String PROPOSE = "propose";
    public static final String REMIND = "remind";
    public static final String STAY_QUIET = "stay_quiet";

    /** 动作工具：会变成发给模组的 command（第一阶段 7 个 + 任务单 11 加的 10 个单步工具）。 */
    public static final Set<String> ACTIONS = Set.of("follow", "come_here", "wait_here", "stop", "pick_up", "deliver",
            "report_status", "goto", "mine", "collect", "craft", "place", "pickup_block", "eat", "sleep", "go_home",
            "remember_place", "use_furnace", "fight");

    /** 计划里允许出现的命令（不含 stop / cancel / report_status / rejoin）。 */
    public static final Set<String> PLANNABLE = Set.of("follow", "come_here", "wait_here", "pick_up", "deliver", "goto",
            "mine", "collect", "craft", "place", "pickup_block", "eat", "sleep", "go_home", "remember_place", "use_furnace", "fight");

    private static final Set<String> REQUIRED_KEYS = Set.of("item", "count", "text", "block", "goal", "steps", "action",
            "say", "name", "input", "kind");

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
        a.add(fn("follow", "开始跟随玩家，一直跟到玩家让你停下。玩家说\"跟我来\"\"跟着我\"\"走\"时用", Map.of()));
        a.add(fn("come_here", "走到玩家身边。玩家说\"过来\"\"到我这来\"时用", Map.of()));
        a.add(fn("wait_here", "停下，在原地等着。玩家说\"等在这\"\"别动\"\"待着\"\"在这等我\"时用，哪怕你现在没在做别的", Map.of()));
        a.add(fn("stop", "立刻停下正在做的事。玩家说\"停\"\"别跟了\"\"停下\"时用", Map.of()));
        a.add(fn("pick_up", "捡起附近地上的某种物品。地上有什么看状态句里的\"地上 8 格内有\"；主人让你捡就调用，背包里已经有同种物品也要去捡，捡没捡到由结果告诉你", Map.of(
                "item", prop("string", "物品的英文 id，例如 minecraft:oak_log"),
                "radius", prop("integer", "搜索半径（格），可不填，默认 8"))));
        a.add(fn("deliver", "把背包里的某种物品交给玩家", Map.of(
                "item", prop("string", "物品的英文 id，例如 minecraft:oak_log"),
                "count", prop("integer", "数量"),
                "to", prop("string", "交给谁的玩家名，可不填，默认是主人"))));
        a.add(fn("report_status", "看看自己现在在哪、有什么、在做什么", Map.of()));
        a.add(fn("report_progress", "主人问\"我们到哪了\"\"接下来该干什么\"\"还缺什么\"这类通关进度的问题时用；后台会算出一句准确的话接在你的回答后面，所以你不要自己说阶段和缺什么，只说一句引子就行", Map.of()));
        // ---- 第二阶段的单步动作（任务单 11）。一句话一个动作就能做完时直接用这些，不要 make_plan ----
        a.add(fn("goto", "走到一个坐标，或走到记过的地点", Map.of(
                "x", prop("number", "坐标 x"), "y", prop("number", "坐标 y"), "z", prop("number", "坐标 z"),
                "place", prop("string", "记过的地点名，如 home / bed；给了 place 就不用坐标"))));
        a.add(fn("mine", "挖指定方块直到拿到指定数量，只能挖白名单里的（原木、原石、矿、沙土）", Map.of(
                "block", prop("string", "方块 id，如 minecraft:oak_log"),
                "count", prop("integer", "要拿到的数量"),
                "radius", prop("integer", "搜索半径（格），可不填，默认 32"))));
        a.add(fn("collect", "捡附近所有掉落物", Map.of(
                "radius", prop("integer", "半径（格），可不填，默认 8"),
                "item", prop("string", "只捡这一种，可不填"))));
        a.add(fn("craft", "合成物品（需要的木板、木棍会顺手做；需要工作台时自己找或放一个）", Map.of(
                "item", prop("string", "物品 id，如 minecraft:stone_pickaxe"),
                "count", prop("integer", "份数，可不填，默认 1"))));
        a.add(fn("place", "放一个方块，只允许 minecraft:crafting_table / minecraft:torch / 自己的床", Map.of(
                "block", prop("string", "方块 id"))));
        a.add(fn("pickup_block", "收回自己放过的方块（默认工作台）", Map.of(
                "block", prop("string", "方块 id，可不填，默认 minecraft:crafting_table"))));
        a.add(fn("eat", "吃背包里的食物；饿了（饥饿低于 14）就该吃", Map.of(
                "item", prop("string", "吃哪种，可不填，默认背包里任意食物"))));
        a.add(fn("sleep", "走到附近或记过的床睡觉，只有晚上能睡", Map.of()));
        a.add(fn("go_home", "回到记过的家（home）", Map.of()));
        a.add(fn("remember_place", "把当前位置记成一个地点名，比如主人说\"记住这里是家\"", Map.of(
                "name", prop("string", "地点名：home / bed 或主人说的名字"))));
        a.add(fn("use_furnace", "用附近的熔炉烧东西", Map.of(
                "input", prop("string", "要烧的物品 id，如 minecraft:raw_iron"),
                "fuel", prop("string", "燃料 id，如 minecraft:coal，可不填"),
                "count", prop("integer", "烧几个，可不填，默认全部"))));
        a.add(fn("fight", "主人明确说打某种怪时用（\"打那只僵尸\"\"打烈焰人\"）：只打这一种，16 格内没有会说没看见，打完回主人身边。村民、动物、玩家不能打", Map.of(
                "kind", prop("string", "怪的英文 id，如 minecraft:zombie / minecraft:skeleton / minecraft:blaze"))));
        a.add(makePlan());
        a.add(remember());
        return a;
    }

    /** make_plan：一句话要几个动作才能做完时用（任务单 11）。 */
    private static JsonObject makePlan() {
        JsonObject step = new JsonObject();
        step.addProperty("type", "object");
        JsonObject sp = new JsonObject();
        JsonObject command = prop("string", "命令名，只能是：" + String.join(" / ", PLANNABLE.stream().sorted().toList()));
        JsonArray en = new JsonArray();
        PLANNABLE.stream().sorted().forEach(en::add);
        command.add("enum", en);
        sp.add("command", command);
        JsonObject args = new JsonObject();
        args.addProperty("type", "object");
        args.addProperty("description", "这条命令的参数，和同名工具的参数一样；没有就给 {}");
        sp.add("args", args);
        sp.add("why", prop("string", "为什么要这一步，几个字"));
        step.add("properties", sp);
        JsonArray req = new JsonArray();
        req.add("command");
        step.add("required", req);
        JsonObject steps = new JsonObject();
        steps.addProperty("type", "array");
        steps.addProperty("description", "按顺序的步骤，最多 8 步");
        steps.add("items", step);
        return fn(MAKE_PLAN, "主人要的事一个动作做不完（比如\"去弄 20 根木头回来\"\"做一把石镐给我\"\"回家睡觉\"）时，"
                + "给出按顺序的几步。能用一个动作做完的事不要用它。参考系统提示里\"常见任务怎么拆\"的例子", Map.of(
                "goal", prop("string", "主人要的事，用原话"),
                "steps", steps));
    }

    /** 计划里某一步失败后，问脑子怎么办时只给这一个工具（任务单 11）。 */
    public static JsonArray planDecision() {
        JsonArray a = new JsonArray();
        JsonObject action = prop("string", "retry = 同一步再试一次；skip = 跳过这步做下一步；replan = 剩下的步骤重排（给 new_steps）；abort = 放弃整件事");
        JsonArray en = new JsonArray();
        for (String s : List.of("retry", "skip", "replan", "abort")) {
            en.add(s);
        }
        action.add("enum", en);
        JsonObject steps = new JsonObject();
        steps.addProperty("type", "array");
        steps.addProperty("description", "action=replan 时，替换掉剩余步骤的新步骤，格式同 make_plan 的 steps");
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        steps.add("items", item);
        a.add(fn(PLAN_DECISION, "决定失败后怎么办，并用一句话告诉主人你要怎么做", Map.of(
                "action", action,
                "say", prop("string", "先对主人说的一句话，像平时说话那样"),
                "new_steps", steps)));
        return a;
    }

    /** 任务单 18：空闲循环"想一想"只给这三个工具；提议的 steps 照旧过 PlanValidator。 */
    public static JsonArray idle() {
        JsonArray a = new JsonArray();
        JsonObject steps = new JsonObject();
        steps.addProperty("type", "array");
        steps.addProperty("description", "怎么做：和 make_plan 的 steps 一样（每步 command / args / why，最多 8 步，只能用工具列表里的命令）");
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        steps.add("items", item);
        a.add(fn(PROPOSE, "主动提议去做一件和当前阶段对得上的事。say 是对主人说的一句话（像平时说话，问一句要不要，不念步骤）", Map.of(
                "say", prop("string", "对主人说的一句话"),
                "goal", prop("string", "一句话目标，例如\"去挖 9 个铁矿\""),
                "steps", steps)));
        JsonObject kind = prop("string", "night = 天黑了该回家；hungry = 饿了；low_health = 血少；danger = 附近有危险（岩浆）");
        JsonArray en = new JsonArray();
        for (String k : List.of("night", "hungry", "low_health", "danger")) {
            en.add(k);
        }
        kind.add("enum", en);
        a.add(fn(REMIND, "提醒主人一件事（天黑、饿了、血少、危险），不提议做事", Map.of(
                "kind", kind,
                "say", prop("string", "对主人说的一句话"))));
        a.add(fn(STAY_QUIET, "现在没什么值得说的，保持安静。大多数时候该选这个", Map.of()));
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
            boolean optional = v.has("description") && v.get("description").getAsString().contains("可不填");
            if (REQUIRED_KEYS.contains(k) && !optional) {
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
