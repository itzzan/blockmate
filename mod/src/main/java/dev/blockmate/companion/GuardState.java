package dev.blockmate.companion;

import java.util.List;

/**
 * BreakGuard 的纯状态部分（任务单 12）：现在允许破坏哪些方块、允许放置没有。
 * 真正写 Baritone 设置的事在 BreakGuard 里；这里只管记账，方便单测。
 */
public final class GuardState {

    private List<String> breakAllowed = List.of();
    private boolean placeOpen;

    /** 返回 true 表示状态真的变了（变了才值得写日志）。 */
    public boolean openBreak(List<String> blockIds) {
        List<String> next = blockIds == null ? List.of() : List.copyOf(blockIds);
        if (next.equals(breakAllowed)) {
            return false;
        }
        breakAllowed = next;
        return true;
    }

    public boolean openPlace() {
        if (placeOpen) {
            return false;
        }
        placeOpen = true;
        return true;
    }

    public boolean closeAll() {
        boolean changed = !breakAllowed.isEmpty() || placeOpen;
        breakAllowed = List.of();
        placeOpen = false;
        return changed;
    }

    public boolean breakOpen() {
        return !breakAllowed.isEmpty();
    }

    public boolean placeOpen() {
        return placeOpen;
    }

    public List<String> breakAllowed() {
        return breakAllowed;
    }

    public String describe() {
        return "break=" + breakAllowed + " place=" + placeOpen;
    }
}
