package dev.blockmate.server.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.DeliveryOutcome;
import dev.blockmate.protocol.OwnerInfo;
import dev.blockmate.protocol.Pos;
import dev.blockmate.protocol.Reason;
import dev.blockmate.protocol.Result;
import dev.blockmate.protocol.Snapshot;
import dev.blockmate.protocol.TaskInfo;
import dev.blockmate.server.gateway.ChatMessage;
import dev.blockmate.server.memory.Episode;
import dev.blockmate.server.memory.StoredEvent;
import dev.blockmate.server.memory.StoredMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 快照摘要、提示词组装、工具定义、结果描述。 */
class BrainUnitTest {

    private static final ItemNames NAMES = ItemNames.load();

    private static Snapshot full() {
        return new Snapshot(true, new Pos(120.5, 64.0, -33.2), "minecraft:overworld", 20.0, 18,
                Map.of("minecraft:oak_log", 8, "minecraft:bread", 3), OwnerInfo.seen(4.2, new Pos(124, 64, -31)),
                new TaskInfo("c1", CommandName.FOLLOW, "running"), Map.of("minecraft:oak_log", 16));
    }

    @Test
    void 物品表能加载() {
        assertTrue(NAMES.size() > 50);
        assertEquals("橡木原木", NAMES.name("minecraft:oak_log"));
        assertEquals("weird_thing", NAMES.name("minecraft:weird_thing"));
    }

    @Test
    void 快照摘要是一句中文() {
        String s = SnapshotSummary.describe(full(), true, NAMES);
        assertTrue(s.contains("主世界"), s);
        assertTrue(s.contains("(121, 64, -33)") || s.contains("(120, 64, -33)"), s);
        assertTrue(s.contains("生命 20/20"), s);
        assertTrue(s.contains("橡木原木 8"), s);
        assertTrue(s.contains("主人在 4 格外"), s);
        assertTrue(s.contains("跟随中"), s);
        assertFalse(s.contains("minecraft:"), "不该出现原始 id: " + s);
    }

    @Test
    void 看不到主人和离线的说法() {
        Snapshot unknown = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null, null);
        String s = SnapshotSummary.describe(unknown, true, NAMES);
        assertTrue(s.contains("没看到主人"), s);
        assertTrue(s.contains("背包：空"), s);
        assertTrue(s.contains("现在没有任务"), s);
        assertFalse(s.contains("地上"), "地上物品不知道就不提：" + s);
        String g = SnapshotSummary.describe(full(), true, NAMES);
        assertTrue(g.contains("地上 8 格内有：橡木原木 16"), g);
        Snapshot empty = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null, Map.of());
        assertTrue(SnapshotSummary.describe(empty, true, NAMES).contains("地上 8 格内没有东西"));
        assertEquals("你现在不在游戏里。", SnapshotSummary.describe(full(), false, NAMES));
        assertTrue(SnapshotSummary.describe(Snapshot.offline(), true, NAMES).contains("没进入服务器"));
    }

    @Test
    void 提示词顺序和角色映射() {
        BrainContext ctx = new BrainContext(full(), true, List.of("晚上十点以后不打游戏"),
                List.of(new Episode("e1", "2026-10-19", "昨天一起砍了树。", 0, 1, 2)),
                List.of(new StoredMessage("m1", 1, "owner", "你好", null, null),
                        new StoredMessage("m2", 2, "companion", "嗯，在呢。", "m1", null),
                        new StoredMessage("m3", 3, "owner_ingame", "快来", null, null),
                        new StoredMessage("m4", 4, "system", "不该出现", null, null)));
        List<ChatMessage> msgs = PromptBuilder.build("我是人设", ctx, NAMES, "跟我来");
        assertEquals("system", msgs.get(0).role());
        String sys = msgs.get(0).content();
        assertTrue(sys.startsWith("我是人设"));
        assertTrue(sys.contains("晚上十点以后不打游戏"));
        assertTrue(sys.contains("2026-10-19：昨天一起砍了树。"));
        assertFalse(sys.contains("现在的状态"), "状态句不在 system 里，前缀才能命中缓存：" + sys);
        assertEquals(List.of("user", "assistant", "user", "user"), msgs.subList(1, 5).stream().map(ChatMessage::role).toList());
        assertEquals("（游戏里说）快来", msgs.get(3).content());
        String last = msgs.get(4).content();
        assertTrue(last.startsWith("现在的状态：你在主世界"), last);
        assertTrue(last.endsWith("\n\n主人说：跟我来"), last);
        assertEquals(5, msgs.size(), "system 角色的历史不给模型看");

        List<ChatMessage> internal = PromptBuilder.build("我是人设", ctx, NAMES, "[系统] 说一句", false);
        String lastInternal = internal.get(internal.size() - 1).content();
        assertTrue(lastInternal.startsWith("现在的状态："), lastInternal);
        assertTrue(lastInternal.endsWith("\n\n[系统] 说一句"), "内部提示不加\"主人说\"：" + lastInternal);
    }

    @Test
    void 日记里的事件按动作汇总() {
        List<StoredEvent> ev = List.of(
                new StoredEvent("e1", 1, "command", "c1", null, "{\"name\":\"follow\"}"),
                new StoredEvent("e2", 2, "result", "c1", null, "{\"command_id\":\"c1\",\"status\":\"cancelled\"}"),
                new StoredEvent("e3", 3, "command", "c2", null, "{\"name\":\"pick_up\"}"),
                new StoredEvent("e4", 4, "result", "c2", null, "{\"command_id\":\"c2\",\"status\":\"ok\"}"),
                new StoredEvent("e5", 5, "command", "c3", null, "{\"name\":\"deliver\"}"),
                new StoredEvent("e6", 6, "result", "c3", null, "{\"command_id\":\"c3\",\"status\":\"failed\",\"reason\":\"target_lost\"}"),
                new StoredEvent("e7", 7, "death", null, null, "{}"),
                new StoredEvent("e8", 8, "death", null, null, "{}"),
                new StoredEvent("e9", 9, "progress", "c2", null, "{}"),
                new StoredEvent("e10", 10, "executor_online", null, null, "{}"));
        String d = EpisodeDigest.describe(ev);
        assertTrue(d.contains("跟随：主人让我做了 1 次，中途换成别的事 1 次"), d);
        assertTrue(d.contains("捡东西：主人让我做了 1 次，做成 1 次") || d.contains("捡起：主人让我做了 1 次，做成 1 次"), d);
        assertTrue(d.contains("主人让我做了 1 次，没做成 1 次"), d);
        assertTrue(d.contains("我死了 2 次"), d);
        assertTrue(d.contains("我进了 1 次游戏"), d);
        assertFalse(d.contains("progress"), d);
        assertEquals("", EpisodeDigest.describe(List.of()));
    }

    @Test
    void 工具定义() {
        JsonArray all = Tools.all();
        assertEquals(22, all.size());
        List<String> names = all.asList().stream()
                .map(e -> ((JsonObject) e).getAsJsonObject("function").get("name").getAsString()).toList();
        assertEquals(List.of("follow", "come_here", "wait_here", "stop", "pick_up", "deliver", "report_status",
                "report_progress", "goto", "mine", "collect", "craft", "place", "pickup_block", "eat", "sleep", "go_home", "remember_place",
                "use_furnace", "fight", "make_plan", "remember_preference"), names);
        JsonObject deliver = all.get(5).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        assertTrue(deliver.getAsJsonObject("properties").has("count"));
        JsonObject mine = all.get(9).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        assertEquals(List.of("block", "count"), mine.getAsJsonArray("required").asList().stream().map(e -> e.getAsString()).sorted().toList(),
                "mine 的 block/count 必填，radius 可不填");
        JsonObject craft = all.get(11).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        assertEquals(List.of("item"), craft.getAsJsonArray("required").asList().stream().map(e -> e.getAsString()).toList(),
                "craft 的 count 可不填");
        JsonObject makePlan = all.get(20).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        JsonObject stepItem = makePlan.getAsJsonObject("properties").getAsJsonObject("steps").getAsJsonObject("items");
        assertTrue(stepItem.getAsJsonObject("properties").getAsJsonObject("command").getAsJsonArray("enum").asList().stream()
                .map(e -> e.getAsString()).toList().containsAll(List.of("mine", "deliver", "go_home")));
        assertFalse(stepItem.getAsJsonObject("properties").getAsJsonObject("command").getAsJsonArray("enum").toString().contains("\"stop\""),
                "stop 不能进计划");
        assertEquals(1, Tools.planDecision().size());
        assertEquals(1, Tools.chatOnly().size());
        assertTrue(Tools.isAction("follow"));
        assertFalse(Tools.isAction("remember_preference"));
        assertEquals(CommandName.COME_HERE, Tools.commandOf("come_here"));
    }

    @Test
    void 结果描述() {
        assertEquals("成功", Brain.describeResult(CommandName.FOLLOW, Result.ok("c")));
        assertEquals("失败，原因：卡住了，动不了", Brain.describeResult(CommandName.COME_HERE, Result.failed("c", Reason.STUCK)));
        assertEquals("被新的指令打断，没做完", Brain.describeResult(CommandName.FOLLOW, Result.cancelled("c")));
        assertTrue(Brain.describeResult(CommandName.DELIVER,
                Result.ok("c", new Result.DeliverDetail(DeliveryOutcome.THROWN_UNCONFIRMED, 8))).contains("不知道收没收到"));
        assertEquals("失败，原因：身体没反应", Brain.describeResult(CommandName.COME_HERE, Result.failed("c", Reason.EXECUTOR_UNRESPONSIVE)));
        // 任务单 13 / 14：把数量和缺的材料说出来
        assertEquals("成功，挖到了 12 个，在你自己的背包里", Brain.describeResult(CommandName.MINE, Result.ok("c", new Result.MineDetail(12))));
        assertTrue(Brain.describeResult(CommandName.MINE, Result.failed("c", Reason.STUCK, new Result.MineDetail(3))).endsWith("，之前已经挖到 3 个"));
        assertEquals("成功，做出来 4 个，在你自己的背包里", Brain.describeResult(CommandName.CRAFT, Result.ok("c", new Result.CraftDetail(4))));
        String miss = Brain.describeResult(CommandName.CRAFT, Result.failed("c", Reason.NO_MATERIALS,
                new Result.CraftDetail(0, java.util.Map.of("minecraft:oak_planks", 2))));
        assertTrue(miss.contains("缺 minecraft:oak_planks×2"), miss);
        assertEquals("成功，到了", Brain.describeResult(CommandName.GO_HOME, Result.ok("c", new Result.GotoDetail(1.2))));
        assertEquals("成功，烧好了 8 个，在你自己的背包里", Brain.describeResult(CommandName.USE_FURNACE, Result.ok("c", new Result.UseFurnaceDetail(8))));
        assertTrue(Brain.describeResult(CommandName.USE_FURNACE, Result.failed("c", Reason.NO_MATERIALS, new Result.UseFurnaceDetail(3))).endsWith("，之前已经烧好 3 个"));
        assertEquals("成功，记住这个地点了", Brain.describeResult(CommandName.REMEMBER_PLACE,
                Result.ok("c", new Result.RememberPlaceDetail("home", new dev.blockmate.protocol.Pos(1, 2, 3)))));
    }

    @Test
    void 任务单17_进度在system里_地标在状态句里() {
        BrainContext ctx = new BrainContext(full(), true, List.of(), List.of(), List.of(), List.of(), List.of(),
                "现在在「石器」阶段（9 个阶段里的第 2 个），缺：熔炉 0/1。做个熔炉。", List.of("村庄（西北 60 格）", "矿点（东 12 格）"));
        List<ChatMessage> msgs = PromptBuilder.build("我是人设", ctx, NAMES, "村庄在哪");
        String sys = msgs.get(0).content();
        assertTrue(sys.contains("通关进度（后台算出来的事实"), sys);
        assertTrue(sys.contains("现在在「石器」阶段"), sys);
        assertFalse(sys.contains("村庄（西北 60 格）"), "地标随位置变，不进 system：" + sys);
        String last = msgs.get(msgs.size() - 1).content();
        assertTrue(last.contains("附近记得的地标：村庄（西北 60 格）、矿点（东 12 格）。"), last);
        assertTrue(last.endsWith("\n\n主人说：村庄在哪"), last);
        BrainContext plain = new BrainContext(full(), true, List.of(), List.of(), List.of());
        String sys2 = PromptBuilder.build("我是人设", plain, NAMES, "你好").get(0).content();
        assertFalse(sys2.contains("通关进度"), "没有进度那句就不加这一段");
        assertFalse(PromptBuilder.stateLine(plain, NAMES).contains("地标"));
        assertEquals("今天", PromptBuilder.dayLabel(System.currentTimeMillis()));
        assertFalse(PromptBuilder.dayLabel(System.currentTimeMillis() - 3L * 24 * 3600 * 1000).equals("今天"));
    }
}
