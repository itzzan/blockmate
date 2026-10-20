package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** event.kind（细则第 6 节）。 */
public enum EventKind {
    @SerializedName("chat") CHAT,
    @SerializedName("death") DEATH,
    @SerializedName("respawn") RESPAWN,
    @SerializedName("kicked") KICKED,
    @SerializedName("disconnected") DISCONNECTED,
    @SerializedName("joined") JOINED,
    @SerializedName("inventory_change") INVENTORY_CHANGE
}
