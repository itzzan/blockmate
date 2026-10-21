package dev.blockmate.companion;

import java.util.ArrayDeque;
import java.util.Deque;

/** 最近收到的"谁捡了什么"（由 Mixin 经 DeliveryHooks 写入），Executor 用它判"已确认接收"。线程安全。 */
public final class DeliveryWatch {

    public record Take(int entityId, int playerId, String itemId, int amount, long atMs) {
    }

    private static final int KEEP = 64;
    private static final Deque<Take> RECENT = new ArrayDeque<>();

    private DeliveryWatch() {
    }

    /** 原版客户端对同一个包会把处理方法跑两次（网络线程一次、主线程一次），这里把 100 ms 内的重复记录去掉。 */
    public static synchronized void record(Take take) {
        Take last = RECENT.peekLast();
        if (last != null && last.entityId() == take.entityId() && last.playerId() == take.playerId()
                && take.atMs() - last.atMs() < 100) {
            return;
        }
        RECENT.addLast(take);
        while (RECENT.size() > KEEP) {
            RECENT.removeFirst();
        }
    }

    /** sinceMs 之后，playerId 是否捡起过 itemId（itemId 为 null 时不限种类；记录里种类未知也算）。 */
    public static synchronized boolean confirmedSince(int playerId, String itemId, long sinceMs) {
        for (Take t : RECENT) {
            if (t.atMs() >= sinceMs && t.playerId() == playerId
                    && (itemId == null || t.itemId() == null || itemId.equals(t.itemId()))) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void clear() {
        RECENT.clear();
    }

    public static synchronized int size() {
        return RECENT.size();
    }
}
