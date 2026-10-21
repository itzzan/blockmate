package dev.blockmate.companion;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackoffTest {

    @Test
    void 退避序列_2_4_8_16_封顶30() {
        assertEquals(2, Backoff.delaySeconds(0, 30));
        assertEquals(4, Backoff.delaySeconds(1, 30));
        assertEquals(8, Backoff.delaySeconds(2, 30));
        assertEquals(16, Backoff.delaySeconds(3, 30));
        assertEquals(30, Backoff.delaySeconds(4, 30));
        assertEquals(30, Backoff.delaySeconds(50, 30));
        assertEquals(2, Backoff.delaySeconds(9, 2));
    }

    @Test
    void token拼进地址() {
        assertEquals("ws://mini:8787/ws/mc?token=a%26b", HubLink.withToken(URI.create("ws://mini:8787/ws/mc"), "a&b").toString());
    }
}
