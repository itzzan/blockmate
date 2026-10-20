package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** result.status（细则 7.4）。 */
public enum ResultStatus {
    @SerializedName("ok") OK,
    @SerializedName("failed") FAILED,
    @SerializedName("cancelled") CANCELLED
}
