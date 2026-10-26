package dev.blockmate.protocol;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 5.3 第 4 条：deliver 三态；snapshot 允许 unknown。 */
class BodyRecordsTest {

    @Test
    void deliver三态都能表达() {
        Result confirmed = Result.ok("c-1", new Result.DeliverDetail(DeliveryOutcome.CONFIRMED, 8));
        Result unknown = Result.ok("c-1", new Result.DeliverDetail(DeliveryOutcome.THROWN_UNCONFIRMED, 8));
        Result notThrown = Result.failed("c-1", Reason.TARGET_TOO_FAR,
                new Result.DeliverDetail(DeliveryOutcome.NOT_THROWN, 0));

        assertEquals(DeliveryOutcome.CONFIRMED, confirmed.detailAs(Result.DeliverDetail.class).delivery());
        assertEquals(DeliveryOutcome.THROWN_UNCONFIRMED, unknown.detailAs(Result.DeliverDetail.class).delivery());
        assertEquals(DeliveryOutcome.NOT_THROWN, notThrown.detailAs(Result.DeliverDetail.class).delivery());
        assertEquals(ResultStatus.FAILED, notThrown.status());
        assertEquals(Reason.TARGET_TOO_FAR, notThrown.reason());
    }

    @Test
    void snapshot可以说不知道() {
        Snapshot justReconnected = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null, null);
        assertFalse(ProtocolCodec.gson().toJson(justReconnected).contains("ground_items"), "不知道就不输出");
        Snapshot withGround = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null,
                Map.of("minecraft:oak_log", 8));
        assertTrue(ProtocolCodec.gson().toJson(withGround).contains("\"ground_items\":{\"minecraft:oak_log\":8}"));
        String json = ProtocolCodec.gson().toJson(justReconnected);
        Snapshot back = ProtocolCodec.gson().fromJson(json, Snapshot.class);
        assertTrue(back.inWorld());
        assertNull(back.pos());
        assertNull(back.health());
        assertFalse(back.owner().visible());
        assertNull(back.owner().distance());
        assertNull(back.task());
        assertFalse(json.contains("pos"), "未知字段不应出现: " + json);
    }

    @Test
    void snapshot离线态() {
        Snapshot off = Snapshot.offline();
        assertFalse(off.inWorld());
        assertFalse(off.owner().visible());
    }

    @Test
    void 命令参数能取成record() {
        Command c = Command.of(CommandName.PICK_UP, new Command.PickUpArgs("minecraft:oak_log", null));
        Command.PickUpArgs args = c.argsAs(Command.PickUpArgs.class);
        assertEquals("minecraft:oak_log", args.item());
        assertEquals(8, args.radiusOrDefault());

        Command d = Command.of(CommandName.DELIVER, new Command.DeliverArgs("minecraft:oak_log", 8, null));
        assertEquals(8, d.argsAs(Command.DeliverArgs.class).count());
        assertNull(d.argsAs(Command.DeliverArgs.class).to());
    }

    @Test
    void keeper状态的字段名() {
        KeeperStatus s = new KeeperStatus(true, 12345, "running", 1760950000000L, 1, false, "0.1.0");
        String json = ProtocolCodec.gson().toJson(s);
        assertTrue(json.contains("\"restarts_10min\":1"), json);
        assertTrue(json.contains("\"gave_up\":false"), json);
        assertTrue(json.contains("\"last_start_ts\":1760950000000"), json);
    }

    @Test
    void 状态里keeper一栏的字段名() {
        Status.KeeperState k = new Status.KeeperState(true, false, "stopped", false, true, null);
        String json = ProtocolCodec.gson().toJson(k);
        assertTrue(json.contains("\"auto\":true"), json);
        assertTrue(json.contains("\"gave_up\":false"), json);
        assertFalse(json.contains("owner_online"), "null 字段不输出：" + json);
        Status.KeeperState off = Status.KeeperState.disconnected(null, null);
        assertFalse(off.connected());
        assertNull(off.auto());
    }

    @Test
    void 任务单9的命令参数和结果细节() {
        Command g = Command.of(CommandName.GOTO, new Command.GotoArgs(null, null, null, "home"));
        assertFalse(g.argsAs(Command.GotoArgs.class).hasPos());
        assertEquals("home", g.argsAs(Command.GotoArgs.class).place());
        Command m = Command.of(CommandName.MINE, new Command.MineArgs("minecraft:oak_log", 20, null));
        assertEquals(32, m.argsAs(Command.MineArgs.class).radiusOrDefault());
        assertTrue(ProtocolCodec.gson().toJson(m).contains("\"count\":20"), ProtocolCodec.gson().toJson(m));
        assertEquals(1, Command.of(CommandName.CRAFT, new Command.CraftArgs("minecraft:stone_pickaxe", null))
                .argsAs(Command.CraftArgs.class).countOrDefault());
        assertFalse(CommandName.REMEMBER_PLACE.occupiesSlot());
        assertTrue(CommandName.MINE.occupiesSlot());
        assertEquals(CommandName.USE_FURNACE, CommandName.fromWire("use_furnace"));
        assertNull(CommandName.fromWire("fly"));
        String r = ProtocolCodec.gson().toJson(Result.failed("c9", Reason.NO_MATERIALS, new Result.MineDetail(12)));
        assertTrue(r.contains("\"reason\":\"no_materials\""), r);
        assertTrue(r.contains("\"mined\":12"), r);
        String e = ProtocolCodec.gson().toJson(Result.ok("c10", new Result.EatDetail("minecraft:bread", 20)));
        assertTrue(e.contains("\"food_after\":20"), e);
    }

    @Test
    void plan消息和快照新字段() {
        Plan plan = new Plan("p1", "去弄 20 根木头回来", List.of(
                new Plan.Step(0, CommandName.MINE, ProtocolCodec.toBody(new Command.MineArgs("minecraft:oak_log", 20, null)),
                        PlanStepState.RUNNING),
                new Plan.Step(1, CommandName.COME_HERE, new JsonObject(), PlanStepState.PENDING),
                new Plan.Step(2, CommandName.DELIVER, ProtocolCodec.toBody(new Command.DeliverArgs("minecraft:oak_log", 20, null)),
                        PlanStepState.PENDING)), 0);
        String json = ProtocolCodec.gson().toJson(plan);
        assertTrue(json.contains("\"plan_id\":\"p1\""), json);
        assertTrue(json.contains("\"state\":\"running\""), json);
        assertTrue(json.contains("\"command\":\"mine\""), json);
        Plan back = ProtocolCodec.gson().fromJson(json, Plan.class);
        assertEquals(3, back.steps().size());
        assertEquals(PlanStepState.PENDING, back.steps().get(2).state());
        assertEquals(0, back.current());

        Snapshot s = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null, Map.of(),
                13000, true, List.of(new Snapshot.InventorySlot(9, "minecraft:crafting_table", 1)));
        String sj = ProtocolCodec.gson().toJson(s);
        assertTrue(sj.contains("\"time_of_day\":13000"), sj);
        assertTrue(sj.contains("\"is_raining\":true"), sj);
        assertTrue(sj.contains("\"slots\":[{\"slot\":9,\"item\":\"minecraft:crafting_table\",\"count\":1}]"), sj);
        Snapshot old = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null, null);
        assertNull(old.timeOfDay());
        assertFalse(ProtocolCodec.gson().toJson(old).contains("time_of_day"), "旧模组不发就不输出");
    }

    @Test
    void hello工厂方法() {
        assertEquals("mc", Hello.mc("t", "Blockmate", "Steve", "0.1.0").role());
        assertEquals(ProtocolCodec.VERSION, Hello.mc("t", "Blockmate", "Steve", "0.1.0").protocolV());
        assertEquals("ui", Hello.ui("t", "web").role());
        assertEquals("keeper", Hello.keeper("t", "0.1.0").role());
        assertTrue(HelloOk.ok(1).ok());
        assertFalse(HelloOk.rejected("token 不对").ok());
        HelloOk mc = HelloOk.mc(1, new BlockRules(java.util.List.of("minecraft:oak_log"), java.util.List.of("minecraft:torch")));
        assertEquals(1, mc.rules().mineable().size(), "回模组的 hello 带白名单");
        assertTrue(ProtocolCodec.gson().toJson(mc).contains("\"rules\":{\"mineable\""));
        assertTrue(BlockRules.empty().placeable().isEmpty(), "空清单 = 什么都不许");
        BlockRules withDrops = new BlockRules(java.util.List.of("minecraft:stone"), java.util.List.of(),
                java.util.Map.of("minecraft:stone", "minecraft:cobblestone"));
        assertEquals("minecraft:cobblestone", withDrops.dropOf("minecraft:stone"), "石头掉圆石");
        assertEquals("minecraft:oak_log", withDrops.dropOf("minecraft:oak_log"), "没写的和方块同名");
        assertEquals("minecraft:dirt", new BlockRules(java.util.List.of(), java.util.List.of(), null).dropOf("minecraft:dirt"), "老后台没发 drops 也不炸");
    }
}
