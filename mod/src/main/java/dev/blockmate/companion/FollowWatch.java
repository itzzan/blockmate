package dev.blockmate.companion;

/**
 * 跟随时的判定（手册 6.4 第 1 条）：主人超过 lostAfterTicks 不在视野 → target_lost；
 * 自己换了维度 → target_other_dim；距离每变化 progressStep 格发一条 progress。纯逻辑。
 */
public final class FollowWatch {

    public enum Outcome { OK, TARGET_LOST, TARGET_OTHER_DIM }

    private final int lostAfterTicks;
    private final double progressStep;
    private final String startDim;
    private int lastSeenTick;
    private Double lastProgressDist;

    public FollowWatch(int lostAfterTicks, double progressStep, int startTick, String startDim) {
        this.lostAfterTicks = lostAfterTicks;
        this.progressStep = progressStep;
        this.startDim = startDim;
        this.lastSeenTick = startTick;
    }

    /** 每 tick 喂一次：主人是否可见、自己现在在哪个维度。 */
    public Outcome feed(boolean ownerVisible, String myDim, int tick) {
        if (myDim != null && startDim != null && !myDim.equals(startDim)) {
            return Outcome.TARGET_OTHER_DIM;
        }
        if (ownerVisible) {
            lastSeenTick = tick;
            return Outcome.OK;
        }
        return tick - lastSeenTick >= lostAfterTicks ? Outcome.TARGET_LOST : Outcome.OK;
    }

    /** 距离变化达到一档就该发 progress；第一次只记基准不发。 */
    public boolean progressDue(double distance) {
        if (lastProgressDist == null) {
            lastProgressDist = distance;
            return false;
        }
        if (Math.abs(distance - lastProgressDist) >= progressStep) {
            lastProgressDist = distance;
            return true;
        }
        return false;
    }
}
