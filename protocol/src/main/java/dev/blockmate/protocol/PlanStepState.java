package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** plan 里每一步的状态（任务单 9）。 */
public enum PlanStepState {
    @SerializedName("pending") PENDING("pending"),
    @SerializedName("running") RUNNING("running"),
    @SerializedName("done") DONE("done"),
    @SerializedName("failed") FAILED("failed"),
    @SerializedName("skipped") SKIPPED("skipped");

    private final String wire;

    PlanStepState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
