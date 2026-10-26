package dev.blockmate.companion;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** 放方块时脚边选位的纯逻辑（任务单 14）：四个水平相邻格里第一个"本身是空、下面站得住"的。 */
public final class PlaceSpot {

    public record Cell(int x, int y, int z) {
        public Cell offset(int dx, int dy, int dz) {
            return new Cell(x + dx, y + dy, z + dz);
        }
    }

    /** 东、南、西、北（先试面前的方向可以由调用方把 facing 传进来排在最前）。 */
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private PlaceSpot() {
    }

    /**
     * @param feet        伙伴脚下那格（blockPosition）
     * @param isFree      这格是空的（空气 / 可替换）
     * @param isSturdyTop 这格顶面是实心的、能放东西
     */
    public static Optional<Cell> pick(Cell feet, Predicate<Cell> isFree, Predicate<Cell> isSturdyTop) {
        for (int[] d : DIRS) {
            Cell c = feet.offset(d[0], 0, d[1]);
            if (isFree.test(c) && isSturdyTop.test(c.offset(0, -1, 0))) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    public static List<Cell> candidates(Cell feet) {
        return List.of(feet.offset(1, 0, 0), feet.offset(0, 0, 1), feet.offset(-1, 0, 0), feet.offset(0, 0, -1));
    }
}
