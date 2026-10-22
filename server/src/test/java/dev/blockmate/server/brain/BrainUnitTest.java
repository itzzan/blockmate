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
                new TaskInfo("c1", CommandName.FOLLOW, "running"));
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
        Snapshot unknown = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null);
        String s = SnapshotSummary.describe(unknown, true, NAMES);
        assertTrue(s.contains("没看到主人"), s);
        assertTrue(s.contains("背包：空"), s);
        assertTrue(s.contains("现在没有任务"), s);
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
    void 工具定义() {
        JsonArray all = Tools.all();
        assertEquals(8, all.size());
        List<String> names = all.asList().stream()
                .map(e -> ((JsonObject) e).getAsJsonObject("function").get("name").getAsString()).toList();
        assertEquals(List.of("follow", "come_here", "wait_here", "stop", "pick_up", "deliver", "report_status",
                "remember_preference"), names);
        JsonObject deliver = all.get(5).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        assertTrue(deliver.getAsJsonObject("properties").has("count"));
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
    }
}
