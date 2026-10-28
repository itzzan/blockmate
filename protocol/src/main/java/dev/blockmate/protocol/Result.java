package dev.blockmate.protocol;

import com.google.gson.JsonObject;

/**
 * 任务结束（细则 7.4）。status=failed 时 reason 必填；detail 按命令不同，用 {@link #detailAs} 取。
 */
public record Result(String commandId, ResultStatus status, Reason reason, JsonObject detail) {

    public static Result ok(String commandId) {
        return new Result(commandId, ResultStatus.OK, null, null);
    }

    public static Result ok(String commandId, Object detail) {
        return new Result(commandId, ResultStatus.OK, null, ProtocolCodec.toBody(detail));
    }

    public static Result failed(String commandId, Reason reason) {
        return new Result(commandId, ResultStatus.FAILED, reason, null);
    }

    public static Result failed(String commandId, Reason reason, Object detail) {
        return new Result(commandId, ResultStatus.FAILED, reason, ProtocolCodec.toBody(detail));
    }

    public static Result cancelled(String commandId) {
        return new Result(commandId, ResultStatus.CANCELLED, null, null);
    }

    public <T> T detailAs(Class<T> cls) {
        return detail == null ? null : ProtocolCodec.gson().fromJson(detail, cls);
    }

    /** deliver 的三态：confirmed / thrown_unconfirmed 配 status=ok；not_thrown 配 status=failed。 */
    public record DeliverDetail(DeliveryOutcome delivery, int count) {
    }

    /** stop：stationary=false 表示三次 cancelEverything 之后仍未静止。 */
    public record StopDetail(boolean stationary, int attempts) {
    }

    public record PickUpDetail(int picked) {
    }

    public record RejoinDetail(int attempt) {
    }

    // ---- 第二阶段（任务单 9）----

    /** goto / go_home：到达时离目标的距离。 */
    public record GotoDetail(double distance) {
    }

    /** mine：实际拿到的数量（失败时也带，说明挖到一半卡住了）。 */
    public record MineDetail(int mined) {
    }

    /** collect：捡到的总数。 */
    public record CollectDetail(int picked) {
    }

    /** remember_place（任务单 13，hub 自己完成）：记下的名字和位置。 */
    public record RememberPlaceDetail(String name, Pos pos) {
    }

    /** craft：合成出的数量。 */
    /** craft：做出来几个；失败（no_materials）时 missing 说缺什么、缺几个（任务单 14）。 */
    public record CraftDetail(int crafted, java.util.Map<String, Integer> missing) {
        public CraftDetail(int crafted) {
            this(crafted, null);
        }
    }

    /** place / pickup_block：方块的位置。 */
    public record PlaceDetail(Pos pos) {
    }

    /** eat：吃的什么、吃完饥饿值。 */
    public record EatDetail(String item, int foodAfter) {
    }

    /** sleep：是否真的睡着并跳过了夜晚。 */
    /** sleep：躺下了没有；bedPos 是床的位置（任务单 15，hub 用它记地点 bed / home）。 */
    public record SleepDetail(boolean slept, Pos bedPos) {
        public SleepDetail(boolean slept) {
            this(slept, null);
        }
    }

    /** use_furnace：烧出来的数量。 */
    /**
     * 任务单 22：build_portal 的结果。inside 是门里那格（点火 / 进门用），placed 放了几块；
     * 失败时 note ∈ no_flat_ground（3 格内没平地）/ frame_incomplete（放完检查少了）/ place_failed（某块放不上），缺材料用 CraftDetail。
     */
    public record BuildPortalDetail(Pos inside, int placed, String note) {
    }

    /** 任务单 22：light_portal 的结果——门里那格。 */
    public record LightPortalDetail(Pos inside) {
    }

    /** 任务单 22：enter_portal 的结果——到了哪个维度。 */
    public record EnterPortalDetail(String dim) {
    }

    /** 任务单 22：explore 的结果——看见了什么（fortress / stronghold）和在哪。 */
    public record ExploreDetail(String found, Pos pos) {
    }

    /** 任务单 20：fight 的结果——打死了几只（失败时也带，表示打了一半）。 */
    public record FightDetail(int killed) {
    }

    public record UseFurnaceDetail(int smelted) {
    }
}
