package dev.blockmate.server.hub;

/**
 * 自动上下线的决策（手册 7.2 可选项）。纯逻辑，hub 线程上用。
 * - 主人从"不在服"变成"在服"的那一刻 → START；第一次观察到在服而伙伴没有期望在线，也算 START（开关打开时你已经在服里）。
 *   只在变化时触发，所以你手动让伙伴下线后它不会马上又被拉起，直到你下次重新进服。
 * - 主人连续不在服满 offlineAfterMs、且伙伴期望在线 → STOP，一次；之后哪怕你又手动让它上线也不再 stop，直到你回来过一次。
 */
public final class PresencePolicy {

    public enum Action { NONE, START, STOP }

    private final long offlineAfterMs;
    private Boolean lastOnline;
    private long offlineSince = -1;
    private boolean stoppedThisAbsence;

    public PresencePolicy(long offlineAfterMs) {
        this.offlineAfterMs = offlineAfterMs;
    }

    public Action observe(boolean ownerOnline, boolean desiredRunning, long now) {
        Action action = Action.NONE;
        if (ownerOnline) {
            boolean edge = lastOnline == null ? !desiredRunning : !lastOnline;
            if (edge && !desiredRunning) {
                action = Action.START;
            }
            offlineSince = -1;
            stoppedThisAbsence = false;
        } else {
            if (lastOnline == null || lastOnline) {
                offlineSince = now;
            }
            if (!stoppedThisAbsence && desiredRunning && now - offlineSince >= offlineAfterMs) {
                action = Action.STOP;
                stoppedThisAbsence = true;
            }
        }
        lastOnline = ownerOnline;
        return action;
    }

    /** 开关重新打开时清零，当作第一次观察。 */
    public void reset() {
        lastOnline = null;
        offlineSince = -1;
        stoppedThisAbsence = false;
    }
}
