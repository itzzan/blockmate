package dev.blockmate.companion;

import dev.blockmate.protocol.Ack;
import dev.blockmate.protocol.Command;
import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.HelloOk;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.ProtocolCodec;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 进入世界连后台，离开世界断开；收到的消息搬到游戏线程处理。 */
public final class ClientEvents {

    private HubLink link;
    private final Observer observer = new Observer(() -> link, () -> null);

    public ClientEvents() {
        MinecraftForge.EVENT_BUS.register(observer);
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        BaritoneBridge.applySettings();
        if (link != null) {
            link.stop();
        }
        link = new HubLink(ClientConfig.HUB_URL.get(), ClientConfig.TOKEN.get(), ClientConfig.PLAYER_NAME.get(),
                ClientConfig.OWNER_NAME.get(), ClientConfig.RECONNECT_MAX_S.get(), this::onHubMessage, this::onHubLost);
        CompanionMod.LOG.info(CompanionMod.TAG + "entered world, connecting hub");
        link.start();
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (link != null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "left world, closing hub link");
            observer.onLeaving();
            link.stop();
            link = null;
        }
    }

    /** WebSocket 线程 → 游戏线程。 */
    private void onHubMessage(Envelope env) {
        Minecraft.getInstance().execute(() -> handle(env));
    }

    private void handle(Envelope env) {
        HubLink l = link;
        if (l == null) {
            return;
        }
        switch (env.type()) {
            case HELLO -> {
                HelloOk ok = ProtocolCodec.bodyAs(env, HelloOk.class);
                if (ok.ok()) {
                    CompanionMod.LOG.info(CompanionMod.TAG + "hub hello ok");
                    observer.announceJoined();
                } else {
                    CompanionMod.LOG.error(CompanionMod.TAG + "hub rejected us: {}", ok.error());
                }
            }
            case COMMAND -> {
                // 任务单 7 之前还不会动：先告诉后台"现在做不了"，免得它等 3 秒
                Command c = ProtocolCodec.bodyAs(env, Command.class);
                CompanionMod.LOG.info(CompanionMod.TAG + "command {} received, executor not implemented yet -> rejected",
                        c.name() == null ? "?" : c.name().wire());
                l.send(l.envelope(MessageType.ACK, Ack.rejected(env.id())));
            }
            default -> CompanionMod.LOG.debug(CompanionMod.TAG + "ignored {}", env.type().wire());
        }
    }

    private void onHubLost() {
        Minecraft.getInstance().execute(BaritoneBridge::cancelEverything);
    }
}
