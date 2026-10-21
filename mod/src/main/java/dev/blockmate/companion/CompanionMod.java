package dev.blockmate.companion;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Blockmate 的控制模组（手册第 6 步）。纯客户端：连后台（link）、看周围（observer）、按命令动（executor）。
 * 任务单 5 只做 link。
 */
@Mod(CompanionMod.MODID)
public final class CompanionMod {

    public static final String MODID = "companion";
    public static final String VERSION = "0.1.0";
    /** 所有日志行的前缀（手册 6.2 第 6 条）。 */
    public static final String TAG = "[companion] ";
    public static final Logger LOG = LogUtils.getLogger();

    public CompanionMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "companion-client.toml");
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        LOG.info(TAG + "loaded version {}", VERSION);
    }
}
