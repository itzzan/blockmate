package dev.blockmate.protocol;

/** ui → hub：对某条偏好点了 confirm 或 ignore。 */
public record PreferenceAction(String id, String action) {
    public static final String CONFIRM = "confirm";
    public static final String IGNORE = "ignore";
}
