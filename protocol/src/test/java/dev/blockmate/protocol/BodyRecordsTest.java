package dev.blockmate.protocol;

import org.junit.jupiter.api.Test;

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
        Snapshot justReconnected = new Snapshot(true, null, null, null, null, Map.of(), OwnerInfo.unknown(), null);
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
    void hello工厂方法() {
        assertEquals("mc", Hello.mc("t", "Blockmate", "Steve", "0.1.0").role());
        assertEquals(ProtocolCodec.VERSION, Hello.mc("t", "Blockmate", "Steve", "0.1.0").protocolV());
        assertEquals("ui", Hello.ui("t", "web").role());
        assertEquals("keeper", Hello.keeper("t", "0.1.0").role());
        assertTrue(HelloOk.ok(1).ok());
        assertFalse(HelloOk.rejected("token 不对").ok());
    }
}
