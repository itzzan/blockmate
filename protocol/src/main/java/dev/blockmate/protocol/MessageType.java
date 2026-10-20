package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/**
 * 信封的 type（细则第 3 节）。
 * 前七个是 /ws/mc 通道的核心类型（手册 5.3）；其余是 /ws/ui 和 /ws/keeper 通道用的。
 */
public enum MessageType {
    // /ws/mc
    @SerializedName("hello") HELLO("hello"),
    @SerializedName("snapshot") SNAPSHOT("snapshot"),
    @SerializedName("event") EVENT("event"),
    @SerializedName("command") COMMAND("command"),
    @SerializedName("ack") ACK("ack"),
    @SerializedName("progress") PROGRESS("progress"),
    @SerializedName("result") RESULT("result"),
    // /ws/ui
    @SerializedName("chat") CHAT("chat"),
    @SerializedName("reply") REPLY("reply"),
    @SerializedName("status") STATUS("status"),
    @SerializedName("stop") STOP("stop"),
    @SerializedName("preferences") PREFERENCES("preferences"),
    @SerializedName("preference_action") PREFERENCE_ACTION("preference_action"),
    @SerializedName("keeper_control") KEEPER_CONTROL("keeper_control"),
    @SerializedName("notice") NOTICE("notice"),
    // /ws/keeper
    @SerializedName("keeper_cmd") KEEPER_CMD("keeper_cmd"),
    @SerializedName("keeper_status") KEEPER_STATUS("keeper_status");

    private final String wire;

    MessageType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
