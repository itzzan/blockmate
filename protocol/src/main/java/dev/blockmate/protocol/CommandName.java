package dev.blockmate.protocol;

import com.google.gson.annotations.SerializedName;

/**
 * 命令名（手册 5.3，细则 7.1）。前九个是第一阶段"帮手"的；后十一个是第二阶段"队友"任务单 9 加的（guide-2 第 1.1 节），
 * 模组逐个实现，没实现的收到就 ack rejected。
 */
public enum CommandName {
    @SerializedName("follow") FOLLOW("follow"),
    @SerializedName("come_here") COME_HERE("come_here"),
    @SerializedName("wait_here") WAIT_HERE("wait_here"),
    @SerializedName("stop") STOP("stop"),
    @SerializedName("pick_up") PICK_UP("pick_up"),
    @SerializedName("deliver") DELIVER("deliver"),
    @SerializedName("report_status") REPORT_STATUS("report_status"),
    @SerializedName("cancel") CANCEL("cancel"),
    @SerializedName("rejoin") REJOIN("rejoin"),
    // ---- 第二阶段（任务单 9）----
    @SerializedName("goto") GOTO("goto"),
    @SerializedName("mine") MINE("mine"),
    @SerializedName("collect") COLLECT("collect"),
    @SerializedName("craft") CRAFT("craft"),
    @SerializedName("place") PLACE("place"),
    @SerializedName("pickup_block") PICKUP_BLOCK("pickup_block"),
    @SerializedName("eat") EAT("eat"),
    @SerializedName("sleep") SLEEP("sleep"),
    @SerializedName("go_home") GO_HOME("go_home"),
    @SerializedName("remember_place") REMEMBER_PLACE("remember_place"),
    @SerializedName("use_furnace") USE_FURNACE("use_furnace"),
    /** 任务单 20：主人明确说"打那只 xx"才有，只打这一种且在 hostile 名单上。 */
    @SerializedName("fight") FIGHT("fight"),
    /** 任务单 21：带路——走在前面，主人落后就等；参数同 goto，另带 landmark（到了说"到村庄了"）。 */
    @SerializedName("lead") LEAD("lead"),
    /** 任务单 21：带路因危险停下后主人说"继续"；不占任务槽。 */
    @SerializedName("continue") CONTINUE("continue");

    private final String wire;

    CommandName(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 是否占用任务槽（stop 和 cancel 是对槽位的操作，不占槽；remember_place 只是记一笔，也不占）。 */
    public boolean occupiesSlot() {
        return this != STOP && this != CANCEL && this != REMEMBER_PLACE && this != CONTINUE;
    }

    /** 按 wire 名找，找不到返回 null。 */
    public static CommandName fromWire(String wire) {
        for (CommandName c : values()) {
            if (c.wire.equals(wire)) {
                return c;
            }
        }
        return null;
    }
}
