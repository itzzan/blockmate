package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** 原因码（手册 5.3，细则 7.5）：前十三个是第一阶段的，后七个是任务单 9 加的。 */
public enum Reason {
    @SerializedName("target_lost") TARGET_LOST("target_lost"),
    @SerializedName("target_other_dim") TARGET_OTHER_DIM("target_other_dim"),
    @SerializedName("target_too_far") TARGET_TOO_FAR("target_too_far"),
    @SerializedName("not_picked") NOT_PICKED("not_picked"),
    @SerializedName("path_failed") PATH_FAILED("path_failed"),
    @SerializedName("stuck") STUCK("stuck"),
    @SerializedName("item_not_found") ITEM_NOT_FOUND("item_not_found"),
    @SerializedName("inventory_full") INVENTORY_FULL("inventory_full"),
    @SerializedName("timeout") TIMEOUT("timeout"),
    @SerializedName("died") DIED("died"),
    @SerializedName("disconnected") DISCONNECTED("disconnected"),
    @SerializedName("executor_unresponsive") EXECUTOR_UNRESPONSIVE("executor_unresponsive"),
    @SerializedName("rejected") REJECTED("rejected"),
    // ---- 第二阶段（任务单 9）----
    @SerializedName("not_allowed") NOT_ALLOWED("not_allowed"),
    @SerializedName("no_materials") NO_MATERIALS("no_materials"),
    @SerializedName("no_recipe") NO_RECIPE("no_recipe"),
    @SerializedName("no_bed") NO_BED("no_bed"),
    @SerializedName("not_night") NOT_NIGHT("not_night"),
    @SerializedName("no_place_known") NO_PLACE_KNOWN("no_place_known"),
    @SerializedName("plan_aborted") PLAN_ABORTED("plan_aborted");

    private final String wire;

    Reason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
