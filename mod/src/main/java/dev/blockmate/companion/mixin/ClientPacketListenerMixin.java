package dev.blockmate.companion.mixin;

import dev.blockmate.companion.DeliveryHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 手册 6.4 第 6 条：交付的"已确认接收"只能靠服务器发来的"某玩家捡起了某物品"这个包判断。
 * 在客户端处理这个包的入口记一笔，Executor 再去查。
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void companion$onTakeItemEntity(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        DeliveryHooks.onTakeItem(packet);
    }
}
