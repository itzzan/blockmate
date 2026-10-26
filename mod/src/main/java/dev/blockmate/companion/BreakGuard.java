package dev.blockmate.companion;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Baritone"能不能破坏 / 放置"开关的唯一入口（任务单 12）。
 * 寻路阶段 allowBreak 永远 false；挖东西时不开 allowBreak，而是把目标方块放进 allowBreakAnyway，
 * 任务结束、失败、被停、断线立刻清空。放方块时 allowPlace 只在放那一下打开然后关回。每次开关都写日志。只在游戏线程调用。
 */
public final class BreakGuard {

    private static final GuardState STATE = new GuardState();

    private BreakGuard() {
    }

    /** 只允许破坏这几种方块（挖的目标）。 */
    public static void allowBreakOnly(List<Block> blocks, String reason) {
        List<String> ids = new ArrayList<>();
        for (Block b : blocks) {
            var key = ForgeRegistries.BLOCKS.getKey(b);
            ids.add(key == null ? b.toString() : key.toString());
        }
        boolean changed = STATE.openBreak(ids);
        try {
            Settings s = BaritoneAPI.getSettings();
            s.allowBreak.value = false;
            s.allowBreakAnyway.value = new ArrayList<>(blocks);
        } catch (Throwable t) {
            CompanionMod.LOG.warn(CompanionMod.TAG + "guard: cannot set allowBreakAnyway: {}", t.toString());
        }
        if (changed) {
            CompanionMod.LOG.info(CompanionMod.TAG + "guard {} reason={}", STATE.describe(), reason);
        }
    }

    public static void allowPlace(String reason) {
        boolean changed = STATE.openPlace();
        try {
            BaritoneAPI.getSettings().allowPlace.value = true;
        } catch (Throwable t) {
            CompanionMod.LOG.warn(CompanionMod.TAG + "guard: cannot set allowPlace: {}", t.toString());
        }
        if (changed) {
            CompanionMod.LOG.info(CompanionMod.TAG + "guard {} reason={}", STATE.describe(), reason);
        }
    }

    /** 全部关回去：任务结束（成功、失败、取消）、停、断线、离开世界都调。 */
    public static void closeAll(String reason) {
        boolean changed = STATE.closeAll();
        try {
            Settings s = BaritoneAPI.getSettings();
            s.allowBreak.value = false;
            s.allowPlace.value = false;
            if (!s.allowBreakAnyway.value.isEmpty()) {
                s.allowBreakAnyway.value = new ArrayList<>();
            }
        } catch (Throwable t) {
            CompanionMod.LOG.warn(CompanionMod.TAG + "guard: cannot reset settings: {}", t.toString());
        }
        if (changed) {
            CompanionMod.LOG.info(CompanionMod.TAG + "guard {} reason={}", STATE.describe(), reason);
        }
    }

    public static boolean breakOpen() {
        return STATE.breakOpen();
    }

    public static boolean placeOpen() {
        return STATE.placeOpen();
    }
}
