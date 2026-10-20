package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** deliver 的三态结果（手册 5.3 第 4 条，细则 7.4）。 */
public enum DeliveryOutcome {
    /** 收到 ClientboundTakeItemEntityPacket 且捡起者是主人。 */
    @SerializedName("confirmed") CONFIRMED,
    /** 已丢出，但 10 秒内没收到确认。绝不自动再丢一次。 */
    @SerializedName("thrown_unconfirmed") THROWN_UNCONFIRMED,
    /** 没丢出去，result.reason 说明原因。 */
    @SerializedName("not_thrown") NOT_THROWN
}
