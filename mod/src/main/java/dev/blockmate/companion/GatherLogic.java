package dev.blockmate.companion;

/** 任务单 13 里不依赖游戏的判定：挖够了没有 / 多久没进展 / 离主人太远没有。纯逻辑，可单测。 */
public final class GatherLogic {

    private GatherLogic() {
    }

    /** 离主人超过这个距离就停下（guide-2 任务单 13）。 */
    public static final double MAX_OWNER_DISTANCE = 64.0;

    /**
     * 主人在服务器里（tab 列表有）但看不见 = 超出同步范围 = 太远；看得见就量距离。主人不在服务器里不限制。
     */
    public static boolean tooFarFromOwner(boolean ownerOnline, boolean ownerVisible, double distance) {
        if (!ownerOnline) {
            return false;
        }
        if (!ownerVisible) {
            return true;
        }
        return distance > MAX_OWNER_DISTANCE;
    }

    /** 按背包里掉落物的数量判断挖够了没有、多久没多一个；人在走动也算有进展（2026-10-27 真游戏预检：站着不动 20 秒才算卡）。 */
    public static final class MineProgress {
        public enum Verdict { CONTINUE, DONE, STALLED }

        private final int startCount;
        private final int wanted;
        private final int stallTicks;
        private int lastCount;
        private int lastIncreaseTick;

        /** @param stallTicks 多少 tick 没多一个算卡住（默认 20 秒 = 400） */
        public MineProgress(int startCount, int wanted, int stallTicks, int nowTick) {
            this.startCount = startCount;
            this.wanted = Math.max(1, wanted);
            this.stallTicks = stallTicks;
            this.lastCount = startCount;
            this.lastIncreaseTick = nowTick;
        }

        public Verdict feed(int nowCount, int tick) {
            return feed(nowCount, false, tick);
        }

        /** @param moving 这段时间人在动（走向目标、挖穿泥土都算有进展，只是背包还没多东西） */
        public Verdict feed(int nowCount, boolean moving, int tick) {
            if (nowCount > lastCount) {
                lastCount = nowCount;
                lastIncreaseTick = tick;
            } else if (moving) {
                lastIncreaseTick = tick;
            }
            if (mined() >= wanted) {
                return Verdict.DONE;
            }
            if (tick - lastIncreaseTick >= stallTicks) {
                return Verdict.STALLED;
            }
            return Verdict.CONTINUE;
        }

        public int mined() {
            return Math.max(0, lastCount - startCount);
        }

        public int wanted() {
            return wanted;
        }
    }
}
