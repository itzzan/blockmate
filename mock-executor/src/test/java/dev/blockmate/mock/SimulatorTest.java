package dev.blockmate.mock;

import dev.blockmate.protocol.Ack;
import dev.blockmate.protocol.Command;
import dev.blockmate.protocol.CommandName;
import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Hello;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.Peer;
import dev.blockmate.protocol.Progress;
import dev.blockmate.protocol.ProtocolCodec;
import dev.blockmate.protocol.Reason;
import dev.blockmate.protocol.Result;
import dev.blockmate.protocol.ResultStatus;
import dev.blockmate.protocol.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 5.4 的行为，全部用假时间驱动，不开网络。 */
class SimulatorTest {

    private final List<Envelope> sent = new ArrayList<>();
    private final List<String> console = new ArrayList<>();
    private Simulator sim;

    private static Options options(boolean failStuck, boolean silent) {
        return new Options(URI.create("ws://127.0.0.1:8787/ws/mc"), "t", "Blockmate", "Steve", failStuck, silent, 1000);
    }

    @BeforeEach
    void setUp() {
        sim = new Simulator(options(false, false), sent::add, console::add);
    }

    private static Envelope command(String id, CommandName name, Object args) {
        return new Envelope(ProtocolCodec.VERSION, MessageType.COMMAND, id, 0, Peer.HUB, Peer.MC,
                ProtocolCodec.toBody(args == null ? Command.of(name) : Command.of(name, args)));
    }

    private List<Envelope> ofType(MessageType type) {
        return sent.stream().filter(e -> e.type() == type).toList();
    }

    private List<Result> results() {
        return ofType(MessageType.RESULT).stream().map(e -> ProtocolCodec.bodyAs(e, Result.class)).toList();
    }

    @Test
    void 连上先发hello() {
        sim.onConnected(0);
        assertEquals(1, sent.size());
        assertEquals(MessageType.HELLO, sent.get(0).type());
        Hello hello = ProtocolCodec.bodyAs(sent.get(0), Hello.class);
        assertEquals("mc", hello.role());
        assertEquals("Blockmate", hello.player());
        assertEquals("mock", hello.modVersion());
        assertEquals(Peer.MC, sent.get(0).from());
    }

    @Test
    void 每秒一条snapshot且内容按手册() {
        sim.onConnected(0);
        sim.tick(500);
        assertEquals(0, ofType(MessageType.SNAPSHOT).size());
        sim.tick(1000);
        sim.tick(1900);
        assertEquals(1, ofType(MessageType.SNAPSHOT).size());
        sim.tick(2000);
        assertEquals(2, ofType(MessageType.SNAPSHOT).size());

        Snapshot s = ProtocolCodec.bodyAs(ofType(MessageType.SNAPSHOT).get(0), Snapshot.class);
        assertTrue(s.inWorld());
        assertEquals(20.0, s.health());
        assertEquals(8, s.inventory().get("minecraft:oak_log"));
        assertTrue(s.owner().visible());
        assertNull(s.task());
    }

    @Test
    void 坐标慢慢变化() {
        sim.onConnected(0);
        sim.tick(1000);
        sim.tick(2000);
        Snapshot a = ProtocolCodec.bodyAs(ofType(MessageType.SNAPSHOT).get(0), Snapshot.class);
        Snapshot b = ProtocolCodec.bodyAs(ofType(MessageType.SNAPSHOT).get(1), Snapshot.class);
        assertNotEquals(a.pos(), b.pos());
        assertTrue(Math.abs(a.pos().x() - b.pos().x()) < 1.0, "每秒只挪一点点");
    }

    @Test
    void 命令流程_ack_progress_result() {
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.FOLLOW, null), 100);
        assertTrue(console.contains("收到 follow"));
        Envelope ack = ofType(MessageType.ACK).get(0);
        assertEquals("c1", ProtocolCodec.bodyAs(ack, Ack.class).commandId());
        assertTrue(ProtocolCodec.bodyAs(ack, Ack.class).accepted());

        sim.tick(900);
        assertEquals(0, ofType(MessageType.PROGRESS).size());
        sim.tick(1100);
        assertEquals(1, ofType(MessageType.PROGRESS).size());
        assertEquals("c1", ProtocolCodec.bodyAs(ofType(MessageType.PROGRESS).get(0), Progress.class).commandId());
        assertEquals(0, results().size());

        sim.tick(3000);
        assertEquals(0, results().size());
        sim.tick(3100);
        assertEquals(1, results().size());
        assertEquals("c1", results().get(0).commandId());
        assertEquals(ResultStatus.OK, results().get(0).status());
        assertFalse(sim.hasTask());

        // 任务进行中 snapshot 里有 task，结束后没有
        sim.tick(4000);
        Snapshot after = ProtocolCodec.bodyAs(ofType(MessageType.SNAPSHOT).get(ofType(MessageType.SNAPSHOT).size() - 1),
                Snapshot.class);
        assertNull(after.task());
    }

    @Test
    void snapshot里带当前任务() {
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.FOLLOW, null), 100);
        sim.tick(1000);
        Snapshot s = ProtocolCodec.bodyAs(ofType(MessageType.SNAPSHOT).get(0), Snapshot.class);
        assertEquals("c1", s.task().commandId());
        assertEquals(CommandName.FOLLOW, s.task().name());
        assertEquals("running", s.task().state());
    }

    @Test
    void 新任务替换旧任务_旧任务先收到cancelled() {
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.FOLLOW, null), 100);
        sim.onMessage(command("c2", CommandName.COME_HERE, null), 500);

        List<Envelope> afterSecond = sent.subList(2, sent.size()); // hello, ack c1, 然后...
        assertEquals(MessageType.RESULT, afterSecond.get(0).type());
        Result cancelled = ProtocolCodec.bodyAs(afterSecond.get(0), Result.class);
        assertEquals("c1", cancelled.commandId());
        assertEquals(ResultStatus.CANCELLED, cancelled.status());
        assertEquals(MessageType.ACK, afterSecond.get(1).type());
        assertEquals("c2", ProtocolCodec.bodyAs(afterSecond.get(1), Ack.class).commandId());

        sim.tick(3200);
        assertEquals(1, results().size(), "c1 不该再有 ok");
        sim.tick(3600);
        assertEquals(2, results().size());
        assertEquals("c2", results().get(1).commandId());
        assertEquals(ResultStatus.OK, results().get(1).status());
    }

    @Test
    void stop立即回result并取消手头任务() {
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.FOLLOW, null), 100);
        sim.onMessage(command("s1", CommandName.STOP, null), 500);
        assertTrue(console.contains("收到 stop"));

        List<Result> rs = results();
        assertEquals(2, rs.size());
        assertEquals("c1", rs.get(0).commandId());
        assertEquals(ResultStatus.CANCELLED, rs.get(0).status());
        assertEquals("s1", rs.get(1).commandId());
        assertEquals(ResultStatus.OK, rs.get(1).status());
        assertTrue(rs.get(1).detailAs(Result.StopDetail.class).stationary());

        sim.tick(5000);
        assertEquals(2, results().size(), "stop 之后不该再冒出 c1 的结果");
        assertFalse(sim.hasTask());
    }

    @Test
    void cancel指定任务() {
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.FOLLOW, null), 100);
        sim.onMessage(command("k1", CommandName.CANCEL, new Command.CancelArgs("c1")), 500);
        List<Result> rs = results();
        assertEquals(2, rs.size());
        assertEquals("c1", rs.get(0).commandId());
        assertEquals(ResultStatus.CANCELLED, rs.get(0).status());
        assertEquals("k1", rs.get(1).commandId());
        assertEquals(ResultStatus.OK, rs.get(1).status());
        assertFalse(sim.hasTask());
    }

    @Test
    void cancel别的任务id不影响手头任务() {
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.FOLLOW, null), 100);
        sim.onMessage(command("k1", CommandName.CANCEL, new Command.CancelArgs("c0")), 500);
        assertTrue(sim.hasTask());
        assertEquals(1, results().size());
        assertEquals("k1", results().get(0).commandId());
    }

    @Test
    void fail_stuck只生效一次() {
        sim = new Simulator(options(true, false), sent::add, console::add);
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.COME_HERE, null), 100);
        sim.tick(3100);
        assertEquals(1, results().size());
        assertEquals(ResultStatus.FAILED, results().get(0).status());
        assertEquals(Reason.STUCK, results().get(0).reason());

        sim.onMessage(command("c2", CommandName.COME_HERE, null), 4000);
        sim.tick(7100);
        assertEquals(2, results().size());
        assertEquals(ResultStatus.OK, results().get(1).status());
    }

    @Test
    void silent收到命令后什么都不回() {
        sim = new Simulator(options(false, true), sent::add, console::add);
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.COME_HERE, null), 100);
        assertTrue(console.contains("收到 come_here"));
        assertEquals(0, ofType(MessageType.ACK).size());
        sim.tick(3100);
        assertEquals(0, results().size());
        assertEquals(0, ofType(MessageType.PROGRESS).size());
        assertTrue(ofType(MessageType.SNAPSHOT).size() >= 1, "snapshot 照常发");
    }

    @Test
    void deliver的结果带三态detail() {
        sim.onConnected(0);
        sim.onMessage(command("d1", CommandName.DELIVER, new Command.DeliverArgs("minecraft:oak_log", 8, "Steve")), 100);
        sim.tick(3100);
        Result r = results().get(0);
        assertEquals(ResultStatus.OK, r.status());
        assertEquals(8, r.detailAs(Result.DeliverDetail.class).count());
    }

    @Test
    void report_status立刻发snapshot并回ok() {
        sim.onConnected(0);
        sim.onMessage(command("r1", CommandName.REPORT_STATUS, null), 100);
        assertEquals(1, ofType(MessageType.SNAPSHOT).size());
        assertEquals("r1", results().get(0).commandId());
        assertFalse(sim.hasTask());
    }

    @Test
    void 断线丢弃任务_重连后不补发() {
        sim.onConnected(0);
        sim.onMessage(command("c1", CommandName.FOLLOW, null), 100);
        sim.onDisconnected();
        assertFalse(sim.hasTask());
        sim.tick(3100);
        assertEquals(0, results().size());
        sim.onConnected(5000);
        assertEquals(2, ofType(MessageType.HELLO).size());
        sim.tick(8100);
        assertEquals(0, results().size());
    }
}
