package dev.blockmate.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** 被踢或掉线后 10 秒自动重连配置里的服务器，最多 5 次（手册 6.4 第 7 条）。在游戏线程上用。 */
public final class Rejoiner {

    private final RejoinPolicy policy = new RejoinPolicy(10_000, 5);

    public boolean enabled() {
        String addr = ClientConfig.SERVER_ADDRESS.get();
        return addr != null && !addr.isBlank();
    }

    /** 断线界面弹出时（Observer 调），reasonKey 是原因的翻译键（可能为 null）。手动退出不会经过这里。 */
    public void onDisconnectedScreen(String reasonKey) {
        if (!enabled()) {
            CompanionMod.LOG.info(CompanionMod.TAG + "rejoin: no server address configured, staying out");
            return;
        }
        if (RejoinPolicy.isPermanent(reasonKey)) {
            policy.cancel();
            CompanionMod.LOG.warn(CompanionMod.TAG + "rejoin: not retrying, server refused us ({})", reasonKey);
            return;
        }
        if (policy.onDisconnected(System.currentTimeMillis())) {
            CompanionMod.LOG.info(CompanionMod.TAG + "rejoin in 10s (attempt {}/5)", policy.attempts() + 1);
        } else {
            CompanionMod.LOG.warn(CompanionMod.TAG + "rejoin: gave up after {} attempts", policy.attempts());
        }
    }

    public void onJoined() {
        if (policy.attempts() > 0) {
            CompanionMod.LOG.info(CompanionMod.TAG + "rejoin succeeded after {} attempt(s)", policy.attempts());
        }
        policy.onJoined();
    }

    /** 后台发来的 rejoin 命令：断开再连。返回这是第几次。 */
    public int forceRejoin(Minecraft mc) {
        if (mc.level != null) {
            mc.level.disconnect();
            mc.clearLevel();
        }
        policy.scheduleIn(System.currentTimeMillis(), 2000);
        return policy.attempts() + 1;
    }

    public void tick(Minecraft mc) {
        if (!policy.due(System.currentTimeMillis()) || mc.player != null || !enabled()) {
            return;
        }
        int n = policy.startAttempt();
        String addr = ClientConfig.SERVER_ADDRESS.get();
        CompanionMod.LOG.info(CompanionMod.TAG + "rejoin: connecting to {} (attempt {})", addr, n);
        try {
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(addr),
                    new ServerData("Blockmate", addr, false), false);
        } catch (Throwable t) {
            CompanionMod.LOG.error(CompanionMod.TAG + "rejoin failed to start: {}", t.toString());
        }
    }
}
