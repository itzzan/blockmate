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
    public record CraftDetail(int crafted) {
    }

    /** place / pickup_block：方块的位置。 */
    public record PlaceDetail(Pos pos) {
    }

    /** eat：吃的什么、吃完饥饿值。 */
    public record EatDetail(String item, int foodAfter) {
    }

    /** sleep：是否真的睡着并跳过了夜晚。 */
    public record SleepDetail(boolean slept) {
    }

    /** use_furnace：烧出来的数量。 */
    public record UseFurnaceDetail(int smelted) {
    }
}
