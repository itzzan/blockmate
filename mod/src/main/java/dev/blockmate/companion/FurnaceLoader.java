package dev.blockmate.companion;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 熔炉的纯逻辑（任务单 14）：没指定燃料时用什么。 */
public final class FurnaceLoader {

    /** 先煤、木炭、煤块，再退到木板、原木。 */
    private static final List<String> PREFERRED = List.of("minecraft:coal", "minecraft:charcoal", "minecraft:coal_block");

    private FurnaceLoader() {
    }

    public static Optional<String> pickFuel(Map<String, Integer> inventory, String requested) {
        if (requested != null && inventory.getOrDefault(requested, 0) > 0) {
            return Optional.of(requested);
        }
        if (requested != null) {
            return Optional.empty();
        }
        for (String id : PREFERRED) {
            if (inventory.getOrDefault(id, 0) > 0) {
                return Optional.of(id);
            }
        }
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getValue() > 0 && e.getKey().endsWith("_planks")) {
                return Optional.of(e.getKey());
            }
        }
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getValue() > 0 && e.getKey().endsWith("_log")) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }
}
