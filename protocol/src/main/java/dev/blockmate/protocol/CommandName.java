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
    @SerializedName("continue") CONTINUE("continue"),
    /** 任务单 22：按图纸搭下界传送门框（黑曜石 10 块，角用黑曜石或临时脚手架）。 */
    @SerializedName("build_portal") BUILD_PORTAL("build_portal"),
    /** 任务单 22：拿打火石点刚搭的门。 */
    @SerializedName("light_portal") LIGHT_PORTAL("light_portal"),
    /** 任务单 22：走进 8 格内的传送门直到换了维度。 */
    @SerializedName("enter_portal") ENTER_PORTAL("enter_portal"),
    /** 任务单 22：Baritone 探索，看见要塞 / 遗迹要塞就停；主人落后 16 格等。 */
    @SerializedName("explore") EXPLORE("explore"),
    /** 任务单 22：计划里的"问主人"步，hub 自己处理不下发；主人说"点 / 好 / 去"继续，"不"整个作废。 */
    @SerializedName("ask_owner") ASK_OWNER("ask_owner");

    private final String wire;

    CommandName(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 是否占用任务槽（stop 和 cancel 是对槽位的操作，不占槽；remember_place 只是记一笔，也不占）。 */
    public boolean occupiesSlot() {
        return this != STOP && this != CANCEL && this != REMEMBER_PLACE && this != CONTINUE && this != ASK_OWNER;
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
