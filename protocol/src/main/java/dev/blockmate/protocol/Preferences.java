package dev.blockmate.protocol;

import java.util.List;

/** hub → ui：待确认的偏好列表。 */
public record Preferences(List<Item> items) {
    public record Item(String id, String text, long ts) {
    }
}
