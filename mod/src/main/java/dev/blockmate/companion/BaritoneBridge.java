package dev.blockmate.companion;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.listener.IGameEventListener;

/** 和 Baritone 打交道的地方。只在游戏线程调用。 */
public final class BaritoneBridge {

    private BaritoneBridge() {
    }

    /** 当前的 Baritone 实例；没有就 null。 */
    public static IBaritone baritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable t) {
            return null;
        }
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
            // 任务单 12：寻路宁可绕路也不顺手挖墙、放方块；allowBreakAnyway 由 BreakGuard 在挖目标时临时填
            s.allowBreakAnyway.value = new java.util.ArrayList<>();
            s.blockBreakAdditionalPenalty.value = 100.0;
            s.blockPlacementPenalty.value = 100.0;
            CompanionMod.LOG.info(CompanionMod.TAG + "baritone provider ok; allowBreak={} allowPlace={} allowParkour={} allowInventory={} followRadius={} breakPenalty={} placePenalty={} baritone={}",
                    s.allowBreak.value, s.allowPlace.value, s.allowParkour.value, s.allowInventory.value,
                    s.followRadius.value, s.blockBreakAdditionalPenalty.value, s.blockPlacementPenalty.value, baritone != null);
            return true;
        } catch (Throwable t) {
            CompanionMod.LOG.error(CompanionMod.TAG + "baritone not available: {}", t.toString());
            return false;
        }
    }

    public static void registerListener(IGameEventListener listener) {
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().registerEventListener(listener);
        } catch (Throwable t) {
            CompanionMod.LOG.warn(CompanionMod.TAG + "cannot register baritone listener: {}", t.toString());
        }
    }

    /** 立刻停下一切（断线、stop 都用它）。返回 Baritone 的返回值。 */
    public static boolean cancelEverything() {
        try {
            boolean r = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            CompanionMod.LOG.info(CompanionMod.TAG + "cancelEverything -> {}", r);
            return r;
        } catch (Throwable t) {
            CompanionMod.LOG.warn(CompanionMod.TAG + "cancelEverything failed: {}", t.toString());
            return false;
        }
    }
}
