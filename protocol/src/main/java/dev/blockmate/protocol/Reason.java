package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** 十三个原因码（手册 5.3，细则 7.5）。 */
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
    @SerializedName("rejected") REJECTED("rejected");

    private final String wire;

    Reason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
