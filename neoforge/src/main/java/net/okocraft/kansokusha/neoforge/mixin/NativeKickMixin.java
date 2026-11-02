package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class NativeKickMixin {
    @WrapMethod(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V")
    private void kansokusha$serverDisconnect(DisconnectionDetails details, Operation<Void> original) {
        var listener = (ServerCommonPacketListenerImpl) (Object) this;
        var player = listener instanceof ServerGamePacketListenerImpl game ? game.getPlayer() : null;
        var level = player != null && player.level() instanceof ServerLevel server ? server : null;
        var position = player == null ? null : player.blockPosition().immutable();
        var actor = player == null ? null : player.getUUID();
        original.call(details);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && level != null)
            recorder.recordKick(level, position, actor, details.reason().getString());
    }
}
