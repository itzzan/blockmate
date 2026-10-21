package dev.blockmate.companion;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.HelloOk;
import dev.blockmate.protocol.ProtocolCodec;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 进入世界连后台，离开世界断开；收到的消息搬到游戏线程处理；每 tick 推进 executor。 */
public final class ClientEvents {

    private HubLink link;
    private final Executor executor;
    private final Observer observer;

    public ClientEvents() {
        this.executor = new Executor(() -> link, this::reportStatus);
        this.observer = new Observer(() -> link, executor::currentTaskInfo);
        MinecraftForge.EVENT_BUS.register(observer);
    }

    private void reportStatus() {
        observer.announceJoined();
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        BaritoneBridge.applySettings();
        executor.attachBaritone();
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
        executor.reset();
        if (link != null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "left world, closing hub link");
            observer.onLeaving();
            link.stop();
            link = null;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            executor.onTick(Minecraft.getInstance());
        }
    }

    /** WebSocket 线程 → 游戏线程。 */
    private void onHubMessage(Envelope env) {
        Minecraft.getInstance().execute(() -> handle(env));
    }

    private void handle(Envelope env) {
        if (link == null) {
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
            case COMMAND -> executor.onCommand(env);
            default -> CompanionMod.LOG.debug(CompanionMod.TAG + "ignored {}", env.type().wire());
        }
    }

    private void onHubLost() {
        Minecraft.getInstance().execute(executor::onLinkLost);
    }
}
