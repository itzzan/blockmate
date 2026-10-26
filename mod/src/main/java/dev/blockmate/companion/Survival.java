package dev.blockmate.companion;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 吃和睡的纯逻辑（任务单 15）：挑哪份食物、现在算不算夜里。 */
public final class Survival {

    /** 一份能吃的东西：背包第几格、id、饱食度。 */
    public record Food(int inventoryIndex, String id, int nutrition) {
    }

    /** 饥饿值到这个数（含）以下就该吃。 */
    public static final int HUNGRY_AT = 14;
    /** guide-2：天黑 = 一天里的 12542–23460 tick。 */
    public static final long NIGHT_START = 12542;
    public static final long NIGHT_END = 23460;

    private Survival() {
    }

    /**
     * 挑"最差的一份"：先在不该吃的以外挑饱食度最低的；都是不该吃的才退而求其次（仍挑饱食度最低）。
     * wanted 不为空时只在那种里挑。
     */
    public static Optional<Food> pick(List<Food> foods, Set<String> avoid, String wanted) {
        Food best = null;
        boolean bestAvoid = true;
        for (Food f : foods) {
            if (wanted != null && !wanted.equals(f.id())) {
                continue;
            }
            boolean av = avoid != null && avoid.contains(f.id());
            if (best == null || (bestAvoid && !av) || (bestAvoid == av && f.nutrition() < best.nutrition())) {
                best = f;
                bestAvoid = av;
            }
        }
        return Optional.ofNullable(best);
    }

    public static boolean isNight(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= NIGHT_START && t <= NIGHT_END;
    }

    public static boolean hungry(int foodLevel) {
        return foodLevel <= HUNGRY_AT;
    }
}
