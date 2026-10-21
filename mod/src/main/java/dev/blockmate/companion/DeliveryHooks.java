package dev.blockmate.companion;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.registries.ForgeRegistries;

/** Mixin 调用的落点：把"谁捡了什么"记进 DeliveryWatch。包到达时实体还在，正好能看物品种类。 */
public final class DeliveryHooks {

    private DeliveryHooks() {
    }

    public static void onTakeItem(ClientboundTakeItemEntityPacket packet) {
        try {
            String itemId = null;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Entity e = mc.level.getEntity(packet.getItemId());
                if (e instanceof ItemEntity item) {
                    var key = ForgeRegistries.ITEMS.getKey(item.getItem().getItem());
                    itemId = key == null ? null : key.toString();
                }
            }
            DeliveryWatch.record(new DeliveryWatch.Take(packet.getItemId(), packet.getPlayerId(), itemId,
                    packet.getAmount(), System.currentTimeMillis()));
            // 这行同时是"Mixin 真的生效了"的证据（手册 6.2 验收要看 latest.log）
            CompanionMod.LOG.info(CompanionMod.TAG + "take_item entity={} player={} item={} amount={}", packet.getItemId(),
                    packet.getPlayerId(), itemId, packet.getAmount());
        } catch (Throwable t) {
            CompanionMod.LOG.debug(CompanionMod.TAG + "take item hook failed: {}", t.toString());
        }
    }
}
