package dev.blockmate.protocol;

/** snapshot 里的主人信息：visible=false 表示没看到（不等于不在），此时 distance / pos 为 null。 */
public record OwnerInfo(boolean visible, Double distance, Pos pos) {

    public static OwnerInfo unknown() {
        return new OwnerInfo(false, null, null);
    }

    public static OwnerInfo seen(double distance, Pos pos) {
        return new OwnerInfo(true, distance, pos);
    }
}
