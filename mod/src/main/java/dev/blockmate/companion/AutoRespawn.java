package dev.blockmate.companion;

/**
 * 死亡画面停留 delayMs 后自动"重生"（2026-10-22 补丁）：挂机时被怪杀死，没人点按钮它会一直停在死亡画面。
 * 等几秒再按，是让 death 事件先发给后台，也不和主人手动点重生打架。画面一直不消失就每 delayMs 再按一次。纯逻辑，游戏线程上用。
 */
public final class AutoRespawn {

    private final long delayMs;
    private long shownSince = -1;

    public AutoRespawn(long delayMs) {
        this.delayMs = delayMs;
    }

    /** 每 tick 调一次。shown = 现在是否显示着死亡画面。返回 true 表示这一刻该按"重生"。 */
    public boolean tick(boolean shown, long nowMs) {
        if (!shown) {
            shownSince = -1;
            return false;
        }
        if (shownSince < 0) {
            shownSince = nowMs;
            return false;
        }
        if (nowMs - shownSince >= delayMs) {
            shownSince = nowMs;
            return true;
        }
        return false;
    }
}
