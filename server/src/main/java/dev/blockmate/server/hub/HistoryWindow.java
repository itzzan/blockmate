package dev.blockmate.server.hub;

import dev.blockmate.server.memory.StoredMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 给模型看的"最近几条对话"用分块滑动的窗口（2026-10-22）：窗口在 min 和 max 条之间，满 max 条时一次砍回 min 条，
 * 而不是每轮掉最老的一条。这样提示的前缀只在每 (max-min) 条消息变一次，其余轮次 Ollama 的前缀缓存都能命中。
 * 窗口的起点用消息 id 记住（锚），后台重启后从头来。纯逻辑，hub 线程上用。
 */
public final class HistoryWindow {

    private final int min;
    private final int max;
    private String anchorId;

    public HistoryWindow(int min, int max) {
        this.min = Math.max(1, min);
        this.max = Math.max(this.min, max);
    }

    /** 传入按时间从旧到新的最近 max 条（Memory.recentMessages 就是这个顺序；多了也行），返回同样顺序的窗口。 */
    public List<StoredMessage> select(List<StoredMessage> oldestFirst) {
        List<StoredMessage> asc = new ArrayList<>(oldestFirst);
        int anchorIdx = -1;
        for (int i = 0; i < asc.size() && anchorId != null; i++) {
            if (anchorId.equals(asc.get(i).id())) {
                anchorIdx = i;
                break;
            }
        }
        if (anchorIdx < 0 || asc.size() - anchorIdx > max) {
            // 没有锚、锚已经太老，或者窗口满了：砍回最近 min 条，重新落锚
            anchorIdx = Math.max(0, asc.size() - min);
            anchorId = asc.isEmpty() ? null : asc.get(anchorIdx).id();
        }
        return new ArrayList<>(asc.subList(anchorIdx, asc.size()));
    }

    public String anchorId() {
        return anchorId;
    }
}
