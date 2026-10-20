package dev.blockmate.protocol;

/** 任务进展（细则 7.3）。note 是短标识：distance / pathing / arrived_near / throwing。 */
public record Progress(String commandId, String note, Double distance, Pos pos) {
}
