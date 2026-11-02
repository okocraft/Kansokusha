package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Captures only changes made by this committed player packet, after other menu protection has run. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class NativeMenuClickMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleContainerClick")
    private void kansokusha$recordClick(ServerboundContainerClickPacket packet, Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null || !player.level().getServer().isSameThread()) {
            original.call(packet);
            return;
        }
        recorder.withContainerClick(player, packet, () -> original.call(packet));
    }
}
