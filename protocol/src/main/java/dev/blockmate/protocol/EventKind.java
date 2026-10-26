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
    @SerializedName("inventory_change") INVENTORY_CHANGE,
    /** 任务单 15：没人叫、自己吃了一口（item = 吃了什么，count = 吃完的饥饿值）。 */
    @SerializedName("auto_eat") AUTO_EAT,
    /** 任务单 15：睡醒后把自己放的床收回来了（pos = 床原来的位置）。 */
    @SerializedName("bed_collected") BED_COLLECTED
}
