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

    // ---- 第二阶段（任务单 9）。args 的细则见 handbook 7.1 ----

    /** goto：给坐标，或给记过的地点名（place，如 home / bed），二选一。 */
    public record GotoArgs(Double x, Double y, Double z, String place) {
        public boolean hasPos() {
            return x != null && y != null && z != null;
        }
    }

    /** mine：挖 block（方块 id，必须在 knowledge/blocks.yaml 白名单里）直到拿到 count 个，radius 默认 32 格。 */
    public record MineArgs(String block, int count, Integer radius) {
        public int radiusOrDefault() {
            return radius == null ? 32 : radius;
        }
    }

    /** collect：捡半径内所有掉落物；item 不为 null 时只捡这一种。radius 默认 8。 */
    public record CollectArgs(Integer radius, String item) {
        public int radiusOrDefault() {
            return radius == null ? 8 : radius;
        }
    }

    /** craft：合成 item（物品 id）count 份（默认 1）；配方问游戏，需要工作台时模组自己找或放。 */
    public record CraftArgs(String item, Integer count) {
        public int countOrDefault() {
            return count == null ? 1 : count;
        }
    }

    /** place：放一个方块，只允许 minecraft:crafting_table / minecraft:torch / 自己的床；pos 为 null 时放在脚边合适处。 */
    public record PlaceArgs(String block, Pos pos) {
    }

    /** pickup_block：收回自己放过的方块（默认工作台）；只能收自己放的。 */
    public record PickupBlockArgs(String block) {
    }

    /** eat：吃 item；为 null 时吃背包里任意食物。 */
    public record EatArgs(String item) {
    }

    /** remember_place：记一个地点名（home / bed / 自定义），pos 为 null 时记当前位置。 */
    public record RememberPlaceArgs(String name, Pos pos) {
    }

    /** use_furnace：把 input 用 fuel 烧 count 个（默认全部）；附近要有熔炉。 */
    public record UseFurnaceArgs(String input, String fuel, Integer count) {
    }
}
