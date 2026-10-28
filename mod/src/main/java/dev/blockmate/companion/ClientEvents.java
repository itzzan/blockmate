package dev.blockmate.companion;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.HelloOk;
import dev.blockmate.protocol.ProtocolCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/** 进入世界连后台，离开世界断开；收到的消息搬到游戏线程处理；每 tick 推进 executor。 */
public final class ClientEvents {

    private HubLink link;
    private final Executor executor;
    private final Observer observer;
    private final Rejoiner rejoiner = new Rejoiner();
    private final AutoRespawn autoRespawn = new AutoRespawn(3_000);
    /** 窗口不在前台时把帧率上限压到这个值（10-23：伙伴的窗口常年在后台，30 帧也吃 40% CPU）。 */
    private static final int BACKGROUND_FPS = 10;
    private Boolean windowActive;
    private int savedFpsLimit = -1;

    public ClientEvents() {
        this.executor = new Executor(() -> link, this::reportStatus, rejoiner);
        this.observer = new Observer(() -> link, executor::currentTaskInfo, rejoiner::onDisconnectedScreen);
        MinecraftForge.EVENT_BUS.register(observer);
    }

    /** 白名单 + 怪物名单 + 战斗开关（任务单 19）；老后台没带名单就什么都不打。 */
    private static Rules buildRules(dev.blockmate.protocol.BlockRules br, dev.blockmate.protocol.MobRules mobs, String combatMode) {
        Rules rules = Rules.of(br.mineable(), br.placeable(), br.dropsOrEmpty(), br.avoidFoodOrEmpty(), br.needsPickaxeOrEmpty());
        if (mobs != null) {
            rules = rules.withCombat(mobs.hostileOrEmpty(), mobs.fleeOnlyOrEmpty(), mobs.neverOrEmpty(), mobs.hostileAtNightOrEmpty(),
                    mobs.hostileIfAttackedOrEmpty(), combatMode);
        }
        return rules;
    }

    private void reportStatus() {
        observer.announceJoined();
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ConfigWatcherFix.stopOnce();
        BaritoneBridge.applySettings();
        executor.attachBaritone();
        rejoiner.onJoined();
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
        HubLink current = link;
        if (current == null) {
            return;
        }
        // LoggingOut 在断线画面弹出之前触发，被踢的原因那时还拿不到；推到下一 tick 再发事件、关链接，
        // 这样被踢会按 kicked（带服务器给的原因）而不是 disconnected 上报。
        Minecraft.getInstance().tell(() -> { // tell 一定排到下一 tick；execute 在游戏线程上会立刻执行
            if (link != current) {
                return; // 期间已经重新进世界、换了新链接
            }
            CompanionMod.LOG.info(CompanionMod.TAG + "left world, closing hub link");
            observer.onLeaving();
            current.stop();
            link = null;
        });
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Minecraft mc = Minecraft.getInstance();
            executor.onTick(mc);
            rejoiner.tick(mc);
            adjustFrameLimit(mc);
            if (autoRespawn.tick(mc.screen instanceof DeathScreen, System.currentTimeMillis()) && mc.player != null) {
                // 和死亡画面上"重生"按钮做的事一样
                CompanionMod.LOG.info(CompanionMod.TAG + "auto respawn");
                mc.player.respawn();
                mc.setScreen(null);
            }
        }
    }

    /** 前台/后台切换时调帧率上限：后台 BACKGROUND_FPS，回前台恢复用户自己的设置。 */
    private void adjustFrameLimit(Minecraft mc) {
        // 不用 mc.isWindowActive()：窗口是 keeper 在后台拉起的，从没拿到过焦点时它一直是初始值 true。直接问 GLFW 当前焦点
        boolean active = GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        if (windowActive != null && windowActive == active) {
            return;
        }
        if (windowActive == null) {
            CompanionMod.LOG.info(CompanionMod.TAG + "window focus at start: {}", active ? "active" : "inactive");
        }
        windowActive = active;
        if (!active) {
            savedFpsLimit = mc.options.framerateLimit().get();
            if (savedFpsLimit > BACKGROUND_FPS) {
                mc.options.framerateLimit().set(BACKGROUND_FPS);
                CompanionMod.LOG.info(CompanionMod.TAG + "window inactive, fps limit {} -> {}", savedFpsLimit, BACKGROUND_FPS);
            }
        } else if (savedFpsLimit > 0) {
            mc.options.framerateLimit().set(savedFpsLimit);
            CompanionMod.LOG.info(CompanionMod.TAG + "window active, fps limit restored to {}", savedFpsLimit);
            savedFpsLimit = -1;
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
                    if (ok.rules() != null) {
                        Rules rules = buildRules(ok.rules(), ok.mobs(), ok.combatMode());
                        executor.setRules(rules);
                        CompanionMod.LOG.info(CompanionMod.TAG + "hub hello ok; 收到白名单 可挖={} 可放={} 能打={} 战斗={}", rules.mineableCount(),
                                rules.placeableCount(), rules.hostile().size(), rules.combatMode());
                    } else {
                        executor.setRules(Rules.EMPTY);
                        CompanionMod.LOG.warn(CompanionMod.TAG + "hub hello ok 但没带白名单，什么都不许挖、不许放（后台版本太旧？）");
                    }
                    observer.announceJoined();
                } else {
                    CompanionMod.LOG.error(CompanionMod.TAG + "hub rejected us: {}", ok.error());
                }
            }
            case RULES -> {
                // 任务单 19：规则变了（战斗开关 / 名单 / 白名单）整份重发，不用重连，也不重发 snapshot / joined
                dev.blockmate.protocol.RulesUpdate ru = ProtocolCodec.bodyAs(env, dev.blockmate.protocol.RulesUpdate.class);
                if (ru.rules() != null) {
                    Rules rules = buildRules(ru.rules(), ru.mobs(), ru.combatMode());
                    executor.setRules(rules);
                    CompanionMod.LOG.info(CompanionMod.TAG + "rules updated 可挖={} 能打={} 战斗={}", rules.mineableCount(),
                            rules.hostile().size(), rules.combatMode());
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
