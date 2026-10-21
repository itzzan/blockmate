package dev.blockmate.companion;

import dev.blockmate.protocol.Envelope;
import dev.blockmate.protocol.Event;
import dev.blockmate.protocol.MessageType;
import dev.blockmate.protocol.OwnerInfo;
import dev.blockmate.protocol.Pos;
import dev.blockmate.protocol.Snapshot;
import dev.blockmate.protocol.TaskInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * observer（手册 6.3）：每 20 tick 发 snapshot，有事发生发 event。全部在游戏线程上。
 * 当前任务由 executor 提供（任务单 7），这里通过 taskSupplier 取。
 */
public final class Observer {

    private static final int SNAPSHOT_EVERY_TICKS = 20;

    private final Supplier<HubLink> link;
    private final Supplier<TaskInfo> taskSupplier;
    private final Runnable onDisconnectScreen;
    private int ticks;
    private boolean wasDead;
    private Map<String, Integer> lastInventory = Inventories.empty();
    private String lastKickReason;

    public Observer(Supplier<HubLink> link, Supplier<TaskInfo> taskSupplier, Runnable onDisconnectScreen) {
        this.link = link;
        this.taskSupplier = taskSupplier;
        this.onDisconnectScreen = onDisconnectScreen;
    }

    // ---------- 每 tick ----------

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        boolean dead = player.isDeadOrDying();
        if (dead && !wasDead) {
            send(MessageType.EVENT, Event.death(pos(player), dim(mc)));
            CompanionMod.LOG.info(CompanionMod.TAG + "event death");
        }
        wasDead = dead;

        if (++ticks % SNAPSHOT_EVERY_TICKS != 0) {
            return;
        }
        Map<String, Integer> inv = inventory(player);
        for (Inventories.Change c : Inventories.diff(lastInventory, inv)) {
            send(MessageType.EVENT, Event.inventoryChange(c.item(), c.delta(), c.count()));
            CompanionMod.LOG.info(CompanionMod.TAG + "event inventory_change item={} delta={} count={}", c.item(), c.delta(), c.count());
        }
        lastInventory = inv;
        send(MessageType.SNAPSHOT, snapshot(mc, player, inv));
    }

    // ---------- 事件 ----------

    /** 重生（手册 6.3 第 3 条）：先完整 snapshot，再 joined，之后才接命令。 */
    @SubscribeEvent
    public void onClone(ClientPlayerNetworkEvent.Clone event) {
        LocalPlayer p = event.getNewPlayer();
        Minecraft mc = Minecraft.getInstance();
        wasDead = false;
        send(MessageType.EVENT, Event.respawn(pos(p), dim(mc)));
        CompanionMod.LOG.info(CompanionMod.TAG + "event respawn");
        announceJoined();
    }

    /** 连上后台（hello ok）或重生后调用：完整 snapshot + joined。 */
    public void announceJoined() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        Map<String, Integer> inv = inventory(player);
        lastInventory = inv;
        send(MessageType.SNAPSHOT, snapshot(mc, player, inv));
        send(MessageType.EVENT, Event.joined());
        CompanionMod.LOG.info(CompanionMod.TAG + "event joined (snapshot sent first)");
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        String owner = ClientConfig.OWNER_NAME.get();
        String text = event.getMessage().getString();
        if (event instanceof ClientChatReceivedEvent.Player p) {
            String sender = senderName(p);
            if (ChatFilter.isOwner(sender, owner)) {
                String body = p.getPlayerChatMessage() != null && p.getPlayerChatMessage().signedContent() != null
                        ? p.getPlayerChatMessage().signedContent() : text;
                send(MessageType.EVENT, Event.chat(owner, body));
                CompanionMod.LOG.info(CompanionMod.TAG + "event chat from_owner=true len={}", body.length());
            } else {
                CompanionMod.LOG.debug(CompanionMod.TAG + "chat dropped (not owner): sender={}", sender);
            }
            return;
        }
        Optional<String> fromOwner = ChatFilter.ownerTextFromSystem(text, owner);
        if (fromOwner.isPresent()) {
            send(MessageType.EVENT, Event.chat(owner, fromOwner.get()));
            CompanionMod.LOG.info(CompanionMod.TAG + "event chat from_owner=true len={} (system)", fromOwner.get().length());
        } else {
            CompanionMod.LOG.debug(CompanionMod.TAG + "chat dropped (system, not owner) len={}", text.length());
        }
    }

    /** 被踢 / 断线的界面弹出时记下原因，LoggingOut 时一起发出去。 */
    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof DisconnectedScreen screen) {
            try {
                Component reason = ObfuscationReflectionHelper.getPrivateValue(DisconnectedScreen.class, screen, "f_95988_");
                lastKickReason = reason == null ? null : reason.getString();
            } catch (Throwable t) {
                lastKickReason = null;
            }
            CompanionMod.LOG.info(CompanionMod.TAG + "disconnected screen, reason len={}", lastKickReason == null ? 0 : lastKickReason.length());
            onDisconnectScreen.run();
        }
    }

    /** 离开世界：ClientEvents 在关链接前调它。 */
    public void onLeaving() {
        String reason = lastKickReason;
        lastKickReason = null;
        if (reason != null && !reason.isBlank()) {
            send(MessageType.EVENT, Event.kicked(reason));
            CompanionMod.LOG.info(CompanionMod.TAG + "event kicked");
        } else {
            send(MessageType.EVENT, Event.disconnected("logout"));
            CompanionMod.LOG.info(CompanionMod.TAG + "event disconnected");
        }
        wasDead = false;
        lastInventory = Inventories.empty();
    }

    // ---------- 采集 ----------

    private Snapshot snapshot(Minecraft mc, LocalPlayer player, Map<String, Integer> inv) {
        return new Snapshot(true, pos(player), dim(mc), (double) player.getHealth(), player.getFoodData().getFoodLevel(),
                inv, owner(mc, player), taskSupplier.get());
    }

    static Pos pos(Player p) {
        return new Pos(round(p.getX()), round(p.getY()), round(p.getZ()));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String dim(Minecraft mc) {
        return mc.level == null ? null : mc.level.dimension().location().toString();
    }

    /** 主人：在当前维度已加载的玩家里按名字找；找不到就是 unknown（手册：看不到不等于不在）。 */
    private OwnerInfo owner(Minecraft mc, LocalPlayer me) {
        String owner = ClientConfig.OWNER_NAME.get();
        for (Player p : mc.level.players()) {
            if (p != me && owner.equals(p.getGameProfile().getName())) {
                return OwnerInfo.seen(round(me.distanceTo(p)), pos(p));
            }
        }
        return OwnerInfo.unknown();
    }

    /** 主背包 + 副手（不含盔甲），物品 id → 数量。 */
    static Map<String, Integer> inventory(LocalPlayer player) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            addStack(out, stack);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            addStack(out, stack);
        }
        return out;
    }

    private static void addStack(Map<String, Integer> out, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        Inventories.add(out, key == null ? null : key.toString(), stack.getCount());
    }

    private static String senderName(ClientChatReceivedEvent.Player p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || p.getSender() == null) {
            return null;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(p.getSender());
        return info == null ? null : info.getProfile().getName();
    }

    private void send(MessageType type, Object body) {
        HubLink l = link.get();
        if (l == null) {
            return;
        }
        Envelope e = l.envelope(type, body);
        l.send(e);
    }
}
