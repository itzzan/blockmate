package dev.blockmate.companion;

/** 卡住判定（手册 6.4 第 2 条）：连续 windowTicks 内位置没变过 minMove 以上。纯逻辑。 */
public final class StuckDetector {

    private final int windowTicks;
    private final double minMove;
    private boolean started;
    private double lx;
    private double ly;
    private double lz;
    private int lastMoveTick;

    public StuckDetector(int windowTicks, double minMove) {
        this.windowTicks = windowTicks;
        this.minMove = minMove;
    }

    /** 每 tick 喂一次当前位置；返回 true 表示已经卡住。 */
    public boolean feed(double x, double y, double z, int tick) {
        if (!started) {
            started = true;
            lx = x;
            ly = y;
            lz = z;
            lastMoveTick = tick;
            return false;
        }
        double d = Math.sqrt((x - lx) * (x - lx) + (y - ly) * (y - ly) + (z - lz) * (z - lz));
        if (d >= minMove) {
            lx = x;
            ly = y;
            lz = z;
            lastMoveTick = tick;
        }
        return tick - lastMoveTick >= windowTicks;
    }

    public void reset() {
        started = false;
    }
}
