package dev.blockmate.server.hub;

import dev.blockmate.server.memory.StoredMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 分块滑动的历史窗口。 */
class HistoryWindowTest {

    /** 按时间从旧到新，和 Memory.recentMessages 一样。 */
    private static List<StoredMessage> oldestFirst(int from, int to) {
        List<StoredMessage> l = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            l.add(new StoredMessage("m" + i, i * 1000L, i % 2 == 0 ? "owner" : "companion", "t" + i, null, null));
        }
        return l;
    }

    @Test
    void 不够min条时全给_并按时间正序() {
        HistoryWindow w = new HistoryWindow(20, 40);
        List<StoredMessage> out = w.select(oldestFirst(1, 5));
        assertEquals(5, out.size());
        assertEquals("m1", out.get(0).id());
        assertEquals("m5", out.get(4).id());
        assertEquals("m1", w.anchorId());
    }

    @Test
    void 锚不动_窗口从min长到max_满了才砍回min() {
        HistoryWindow w = new HistoryWindow(20, 40);
        assertEquals(20, w.select(oldestFirst(1, 20)).size());
        assertEquals("m1", w.anchorId(), "先落锚在第 1 条");
        for (int n = 21; n <= 40; n++) {
            List<StoredMessage> out = w.select(oldestFirst(1, n));
            assertEquals(n, out.size(), "第 " + n + " 条时窗口还在长");
            assertEquals("m1", out.get(0).id(), "起点不变，前缀才能命中缓存");
        }
        List<StoredMessage> out = w.select(oldestFirst(1, 41));
        assertEquals(20, out.size(), "超过 40 条砍回 20 条");
        assertEquals("m22", out.get(0).id());
        assertEquals("m22", w.anchorId());
        assertEquals(21, w.select(oldestFirst(1, 42)).size(), "之后又从新锚开始长");
    }

    @Test
    void 锚不在最近max条里就重新落锚() {
        HistoryWindow w = new HistoryWindow(20, 40);
        w.select(oldestFirst(1, 20));
        List<StoredMessage> out = w.select(oldestFirst(50, 100));
        assertEquals(20, out.size());
        assertEquals("m81", out.get(0).id());
    }

    @Test
    void 空列表() {
        HistoryWindow w = new HistoryWindow(20, 40);
        assertEquals(0, w.select(List.of()).size());
    }
}
