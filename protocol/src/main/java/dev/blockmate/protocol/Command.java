package dev.blockmate.protocol;

import com.google.gson.JsonObject;

/** hub → mc 的命令（细则 7.1）。args 按命令不同，用 {@link #argsAs} 取成对应的 record。 */
public record Command(CommandName name, JsonObject args) {

    public Command {
        if (args == null) {
            args = new JsonObject();
        }
    }

    public static Command of(CommandName name) {
        return new Command(name, new JsonObject());
    }

    public static Command of(CommandName name, Object args) {
        return new Command(name, ProtocolCodec.toBody(args));
    }

    public <T> T argsAs(Class<T> cls) {
        return ProtocolCodec.gson().fromJson(args, cls);
    }

    /** pick_up：item 是物品 id（如 minecraft:oak_log），radius 为 null 时默认 8 格。 */
    public record PickUpArgs(String item, Integer radius) {
        public int radiusOrDefault() {
            return radius == null ? 8 : radius;
        }
    }

    /** deliver：to 为 null 时默认交给主人。 */
    public record DeliverArgs(String item, int count, String to) {
    }

    /** cancel：要取消的那条 command 的信封 id。 */
    public record CancelArgs(String commandId) {
    }
}
