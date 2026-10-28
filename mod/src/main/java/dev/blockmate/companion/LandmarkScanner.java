package dev.blockmate.companion;

import dev.blockmate.protocol.Event;
import dev.blockmate.protocol.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 地标扫描（任务单 17）：每 tick 扫一列（x 固定，z ±24、y ±8，约 830 块），49 tick 扫完一遍，歇 50 tick，约每 5 秒一遍；
 * 扫完找 24 格内的村民，交给 {@link LandmarkLogic} 判断，没报过的发 landmark / danger 事件。全部在游戏线程上。
 */
public final class LandmarkScanner {

    /** 任务单 22：最近一次报出去的地标和时间（explore 看它判断"找到要塞了"）。 */
    public static volatile LandmarkLogic.Found lastFound;
    public static volatile long lastFoundAtMs;

    private final LandmarkLogic logic = new LandmarkLogic();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private LandmarkLogic.Tally tally;
    private int cx;
    private int cy;
    private int cz;
    private int col;
    private int pause;
    private String dim;

    /** 每 tick 调一次；返回这一 tick 要发的事件（通常为空）。 */
    public List<Event> tick(Minecraft mc, LocalPlayer me) {
        if (mc.level == null || me == null) {
            tally = null;
            return List.of();
        }
        if (pause > 0) {
            pause--;
            return List.of();
        }
        if (tally == null) {
            tally = new LandmarkLogic.Tally();
            cx = me.getBlockX();
            cy = me.getBlockY();
            cz = me.getBlockZ();
            col = -LandmarkLogic.RADIUS;
            dim = Observer.dim(mc);
        }
        scanColumn(mc, col);
        col++;
        if (col > LandmarkLogic.RADIUS) {
            List<Event> out = finish(mc, me);
            tally = null;
            pause = LandmarkLogic.PAUSE_TICKS;
            return out;
        }
        return List.of();
    }

    private void scanColumn(Minecraft mc, int dx) {
        int x = cx + dx;
        boolean lavaX = Math.abs(dx) <= LandmarkLogic.LAVA_RADIUS;
        int minY = mc.level.getMinBuildHeight();
        int maxY = mc.level.getMaxBuildHeight() - 1;
        for (int dz = -LandmarkLogic.RADIUS; dz <= LandmarkLogic.RADIUS; dz++) {
            int z = cz + dz;
            if (!mc.level.hasChunkAt(x, z)) {
                continue;
            }
            boolean lavaZ = lavaX && Math.abs(dz) <= LandmarkLogic.LAVA_RADIUS;
            for (int dy = -LandmarkLogic.VERTICAL; dy <= LandmarkLogic.VERTICAL; dy++) {
                int y = cy + dy;
                if (y < minY || y > maxY) {
                    continue;
                }
                BlockState st = mc.level.getBlockState(cursor.set(x, y, z));
                if (st.isAir()) {
                    continue;
                }
                if (st.is(Blocks.BELL)) {
                    tally.hit(LandmarkLogic.BELL, x, y, z);
                } else if (st.is(Blocks.NETHER_PORTAL)) {
                    tally.hit(LandmarkLogic.NETHER_PORTAL, x, y, z);
                } else if (st.is(Blocks.NETHER_BRICKS)) {
                    tally.hit(LandmarkLogic.NETHER_BRICKS, x, y, z);
                } else if (st.is(Blocks.END_PORTAL_FRAME)) {
                    tally.hit(LandmarkLogic.END_PORTAL_FRAME, x, y, z);
                } else if (st.is(Blocks.MOSSY_STONE_BRICKS)) {
                    tally.hit(LandmarkLogic.MOSSY_STONE_BRICKS, x, y, z);
                } else if (st.is(Blocks.STONE_BRICK_STAIRS)) {
                    tally.hit(LandmarkLogic.STONE_BRICK_STAIRS, x, y, z);
                } else if (lavaZ && Math.abs(dy) <= LandmarkLogic.LAVA_RADIUS && st.is(Blocks.LAVA)) {
                    tally.hit(LandmarkLogic.LAVA_NEAR, x, y, z);
                }
            }
        }
    }

    private List<Event> finish(Minecraft mc, LocalPlayer me) {
        int villagers = 0;
        double[] villagerPos = null;
        for (Villager v : mc.level.getEntitiesOfClass(Villager.class, me.getBoundingBox().inflate(LandmarkLogic.RADIUS))) {
            if (!v.isAlive()) {
                continue;
            }
            villagers++;
            if (villagerPos == null) {
                villagerPos = new double[]{v.getX(), v.getY(), v.getZ()};
            }
        }
        List<Event> out = new ArrayList<>();
        for (LandmarkLogic.Found f : LandmarkLogic.classify(tally, dim, villagers, villagerPos)) {
            if (!logic.shouldReport(f, dim)) {
                continue;
            }
            Pos pos = new Pos(f.x(), f.y(), f.z());
            out.add(f.danger() ? Event.danger(f.kind(), pos, dim) : Event.landmark(f.kind(), pos, dim));
            if (!f.danger()) {
                lastFound = f;
                lastFoundAtMs = System.currentTimeMillis();
            }
            CompanionMod.LOG.info(CompanionMod.TAG + "event {} what={} pos=({}, {}, {})", f.danger() ? "danger" : "landmark",
                    f.kind(), Math.round(f.x()), Math.round(f.y()), Math.round(f.z()));
        }
        return out;
    }
}
