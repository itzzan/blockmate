package dev.blockmate.protocol;

/** mc 收到 command 后立刻回（细则 7.2）。accepted=false 时 reason 通常是 rejected。 */
public record Ack(String commandId, boolean accepted, Reason reason) {

    public static Ack accepted(String commandId) {
        return new Ack(commandId, true, null);
    }

    public static Ack rejected(String commandId) {
        return new Ack(commandId, false, Reason.REJECTED);
    }
}
