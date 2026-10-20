package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/** 九个命令（手册 5.3，细则 7.1）。 */
public enum CommandName {
    @SerializedName("follow") FOLLOW("follow"),
    @SerializedName("come_here") COME_HERE("come_here"),
    @SerializedName("wait_here") WAIT_HERE("wait_here"),
    @SerializedName("stop") STOP("stop"),
    @SerializedName("pick_up") PICK_UP("pick_up"),
    @SerializedName("deliver") DELIVER("deliver"),
    @SerializedName("report_status") REPORT_STATUS("report_status"),
    @SerializedName("cancel") CANCEL("cancel"),
    @SerializedName("rejoin") REJOIN("rejoin");

    private final String wire;

    CommandName(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 是否占用任务槽（stop 和 cancel 是对槽位的操作，不占槽）。 */
    public boolean occupiesSlot() {
        return this != STOP && this != CANCEL;
    }
}
