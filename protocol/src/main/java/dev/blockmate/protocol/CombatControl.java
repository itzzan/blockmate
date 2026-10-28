package dev.blockmate.protocol;

/** ui → hub：任务单 19 的战斗开关，mode ∈ off / defend / protect。 */
public record CombatControl(String mode) {
}
