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
}
