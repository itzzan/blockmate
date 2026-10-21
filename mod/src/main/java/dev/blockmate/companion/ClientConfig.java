package dev.blockmate.companion;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/companion-client.toml（手册 6.2 第 3 条，细则第 15 节）。Forge 第一次启动时自动生成带注释的文件。 */
public final class ClientConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> HUB_URL;
    public static final ForgeConfigSpec.ConfigValue<String> TOKEN;
    public static final ForgeConfigSpec.ConfigValue<String> OWNER_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> PLAYER_NAME;
    public static final ForgeConfigSpec.IntValue RECONNECT_MAX_S;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Blockmate 控制模组的配置。改完要重进世界才生效。").push("hub");
        HUB_URL = b.comment("后台的 /ws/mc 地址。部署到 Mac mini 后是 ws://mini:8787/ws/mc；本机开发用 ws://127.0.0.1:8787/ws/mc")
                .define("url", "ws://mini:8787/ws/mc");
        TOKEN = b.comment("后台 config.yaml 里的 token").define("token", "CHANGE_ME");
        b.pop();
        b.comment("玩家名").push("names");
        OWNER_NAME = b.comment("主人的玩家名，只听他的").define("owner", "Steve");
        PLAYER_NAME = b.comment("伙伴自己的玩家名").define("player", "Blockmate");
        b.pop();
        RECONNECT_MAX_S = b.comment("断线重连的退避上限（秒），从 2 秒起翻倍到这个值")
                .defineInRange("reconnect_max_s", 30, 2, 600);
        SPEC = b.build();
    }

    private ClientConfig() {
    }
}
