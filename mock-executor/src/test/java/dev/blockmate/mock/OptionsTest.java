package dev.blockmate.mock;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest {

    @Test
    void 默认值() {
        Options o = Options.parse(new String[0]);
        assertEquals(URI.create("ws://127.0.0.1:8787/ws/mc"), o.hub());
        assertEquals("Blockmate", o.player());
        assertEquals(1000, o.snapshotIntervalMs());
        assertFalse(o.failStuck());
        assertFalse(o.silent());
    }

    @Test
    void 全部参数() {
        Options o = Options.parse(new String[] {"--hub", "ws://mini:8787/ws/mc", "--token", "abc", "--player", "Bob",
                "--owner", "Alice", "--fail", "stuck", "--silent", "--interval", "500"});
        assertEquals("ws://mini:8787/ws/mc", o.hub().toString());
        assertEquals("abc", o.token());
        assertEquals("Bob", o.player());
        assertEquals("Alice", o.owner());
        assertTrue(o.failStuck());
        assertTrue(o.silent());
        assertEquals(500, o.snapshotIntervalMs());
    }

    @Test
    void 不认识的参数报错() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--dance"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--fail", "lava"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--token"}));
    }

    @Test
    void token拼进地址() {
        URI u = WsClient.withToken(URI.create("ws://127.0.0.1:8787/ws/mc"), "a b&c");
        assertEquals("ws://127.0.0.1:8787/ws/mc?token=a+b%26c", u.toString());
    }
}
