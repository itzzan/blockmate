package dev.blockmate.protocol;

import java.util.List;

/**
 * 任务单 19：knowledge/mobs.yaml 的名单，hub 在 hello / rules 里下发给模组。
 * hostile 能打；fleeOnly 只躲不打（苦力怕）；never 永远不碰（玩家、村民、动物…）；
 * hostileAtNight 夜里才算怪（蜘蛛）；hostileIfAttacked 它先动手才算怪（蜘蛛、末影人、僵尸猪灵）。
 * 代码里只有"在 hostile 名单上才打"，没有别的判断。
 */
public record MobRules(List<String> hostile, List<String> fleeOnly, List<String> never, List<String> hostileAtNight,
                       List<String> hostileIfAttacked) {

    public static MobRules empty() {
        return new MobRules(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public List<String> hostileOrEmpty() {
        return hostile == null ? List.of() : hostile;
    }

    public List<String> fleeOnlyOrEmpty() {
        return fleeOnly == null ? List.of() : fleeOnly;
    }

    public List<String> neverOrEmpty() {
        return never == null ? List.of() : never;
    }

    public List<String> hostileAtNightOrEmpty() {
        return hostileAtNight == null ? List.of() : hostileAtNight;
    }

    public List<String> hostileIfAttackedOrEmpty() {
        return hostileIfAttacked == null ? List.of() : hostileIfAttacked;
    }
}
