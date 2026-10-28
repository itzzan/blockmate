package dev.blockmate.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 5.3 第 1 条：消息类型、命令、原因码一个不少，并且 JSON 写法是小写下划线。 */
class EnumCoverageTest {

    @Test
    void 七种核心消息类型() {
        Set<String> wires = Arrays.stream(MessageType.values()).map(MessageType::wire).collect(Collectors.toSet());
        assertTrue(wires.containsAll(List.of("hello", "snapshot", "event", "command", "ack", "progress", "result")));
    }

    @Test
    void 九个命令() {
        Set<String> wires = Arrays.stream(CommandName.values()).map(CommandName::wire).collect(Collectors.toSet());
        assertEquals(Set.of("follow", "come_here", "wait_here", "stop", "pick_up", "deliver", "report_status",
                "cancel", "rejoin", "goto", "mine", "collect", "craft", "place", "pickup_block", "eat", "sleep", "go_home",
                "remember_place", "use_furnace", "fight", "lead", "continue", "build_portal", "light_portal", "enter_portal", "explore", "ask_owner"), wires);
    }

    @Test
    void 十三个原因码() {
        Set<String> wires = Arrays.stream(Reason.values()).map(Reason::wire).collect(Collectors.toSet());
        assertEquals(Set.of("target_lost", "target_other_dim", "target_too_far", "not_picked", "path_failed", "stuck",
                "item_not_found", "inventory_full", "timeout", "died", "disconnected", "executor_unresponsive",
                "rejected",
                "not_allowed", "no_materials", "no_recipe", "no_bed", "not_night", "no_place_known", "plan_aborted"), wires);
    }

    @Test
    void 枚举序列化成小写下划线() {
        assertEquals("\"come_here\"", ProtocolCodec.gson().toJson(CommandName.COME_HERE));
        assertEquals("\"executor_unresponsive\"", ProtocolCodec.gson().toJson(Reason.EXECUTOR_UNRESPONSIVE));
        assertEquals("\"thrown_unconfirmed\"", ProtocolCodec.gson().toJson(DeliveryOutcome.THROWN_UNCONFIRMED));
        assertEquals("\"inventory_change\"", ProtocolCodec.gson().toJson(EventKind.INVENTORY_CHANGE));
        assertEquals(CommandName.PICK_UP, ProtocolCodec.gson().fromJson("\"pick_up\"", CommandName.class));
    }

    @Test
    void stop和cancel不占任务槽() {
        assertTrue(!CommandName.STOP.occupiesSlot());
        assertTrue(!CommandName.CANCEL.occupiesSlot());
        assertTrue(CommandName.FOLLOW.occupiesSlot());
    }
}
