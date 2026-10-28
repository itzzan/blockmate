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
    @SerializedName("bed_collected") BED_COLLECTED,
    /** 任务单 17：看见地标（what = village / portal_overworld / portal_nether / fortress / stronghold / end_portal，pos / dim 是地标位置）。 */
    @SerializedName("landmark") LANDMARK,
    /** 任务单 17：看见危险（what = lava，pos / dim 是岩浆位置），带路和空闲提议用；任务单 19 加 what = overwhelmed（打不过，正往主人跑）。 */
    @SerializedName("danger") DANGER,
    /** 任务单 19：打完一架。what = 对手种类，reason = 结局（killed / fled / gave_up / stopped / died），count = 用时毫秒，delta = 掉的血。 */
    @SerializedName("combat") COMBAT
}
