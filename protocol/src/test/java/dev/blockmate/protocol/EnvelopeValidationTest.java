package dev.blockmate.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 细则第 2 节：信封校验；不认识的字段忽略；省略与 null 等价。 */
class EnvelopeValidationTest {

    private static final String OK = "{\"v\":1,\"type\":\"command\",\"id\":\"x-1\",\"ts\":1,\"from\":\"hub\",\"to\":\"mc\","
            + "\"body\":{\"name\":\"follow\",\"args\":{}}}";

    @Test
    void 合法信封能解析() {
        Envelope e = ProtocolCodec.decode(OK);
        assertEquals(MessageType.COMMAND, e.type());
        assertEquals(Peer.HUB, e.from());
        assertEquals(CommandName.FOLLOW, ProtocolCodec.bodyAs(e, Command.class).name());
    }

    @Test
    void 版本不对要报错() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(OK.replace("\"v\":1", "\"v\":2")));
    }

    @Test
    void 不认识的type要报错() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(OK.replace("\"command\"", "\"dance\"")));
    }

    @Test
    void 缺id要报错() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(OK.replace("\"id\":\"x-1\",", "")));
    }

    @Test
    void 不认识的from要报错() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(OK.replace("\"hub\"", "\"alien\"")));
    }

    @Test
    void 不是JSON对象要报错() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode("[1,2,3]"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode("not json"));
    }

    @Test
    void 不认识的字段被忽略() {
        String withExtra = OK.replace("\"ts\":1,", "\"ts\":1,\"mood\":\"happy\",");
        Envelope e = ProtocolCodec.decode(withExtra);
        assertEquals("x-1", e.id());
        Command c = ProtocolCodec.bodyAs(e, Command.class);
        assertEquals(CommandName.FOLLOW, c.name());
    }

    @Test
    void 缺body等于空对象() {
        Envelope e = ProtocolCodec.decode(OK.replace(",\"body\":{\"name\":\"follow\",\"args\":{}}", ""));
        assertNotNull(e.body());
        assertEquals(new JsonObject(), e.body());
    }

    @Test
    void 新建信封自动填id和ts() {
        Envelope e = ProtocolCodec.envelope(MessageType.COMMAND, Peer.HUB, Peer.MC, Command.of(CommandName.STOP));
        assertEquals(ProtocolCodec.VERSION, e.v());
        assertFalse(e.id().isBlank());
        assertTrue(e.ts() > 0);
        assertEquals("stop", e.body().get("name").getAsString());
        assertEquals(36, e.id().length(), "id 应是 UUID");
    }

    @Test
    void null字段在JSON里省略() {
        String json = ProtocolCodec.gson().toJson(Ack.accepted("c-1"));
        assertFalse(json.contains("reason"), "accepted 的 ack 不应写出 reason: " + json);
        assertEquals("{\"command_id\":\"c-1\",\"accepted\":true}", json);
    }
}
