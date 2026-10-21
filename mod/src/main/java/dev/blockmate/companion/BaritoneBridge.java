package dev.blockmate.companion;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;

/** 和 Baritone 打交道的地方。只在游戏线程调用。 */
public final class BaritoneBridge {

    private BaritoneBridge() {
    }

    /** 进入世界时把设置写死（手册 6.2 第 5 条）。返回 Baritone 是否可用。 */
    public static boolean applySettings() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            Settings s = BaritoneAPI.getSettings();
            s.allowBreak.value = false;
            s.allowPlace.value = false;
            s.allowParkour.value = false;
            s.allowInventory.value = false;
            s.followRadius.value = 3;
            CompanionMod.LOG.info(CompanionMod.TAG + "baritone provider ok; allowBreak={} allowPlace={} allowParkour={} allowInventory={} followRadius={} baritone={}",
                    s.allowBreak.value, s.allowPlace.value, s.allowParkour.value, s.allowInventory.value,
                    s.followRadius.value, baritone != null);
            return true;
        } catch (Throwable t) {
            CompanionMod.LOG.error(CompanionMod.TAG + "baritone not available: {}", t.toString());
            return false;
        }
    }

    /** 立刻停下一切（断线、stop 都用它）。 */
    public static void cancelEverything() {
        try {
            boolean r = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "cancelEverything -> {}", r);
        } catch (Throwable t) {
            CompanionMod.LOG.warn(CompanionMod.TAG + "cancelEverything failed: {}", t.toString());
        }
    }
}
