package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.okocraft.kansokusha.neoforge.NativeEntityRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Completes each player action before another action on the same entity can change its state. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class NativeEntityInteractionMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleInteract")
    private void kansokusha$interact(ServerboundInteractPacket packet, Operation<Void> original) {
        if (!player.level().getServer().isSameThread()) {
            original.call(packet);
            return;
        }
        NativeEntityRecorder.beginEntityInteraction(player, player.level().getEntityOrPart(packet.entityId()),
            packet.hand());
        try {
            original.call(packet);
        } finally {
            NativeEntityRecorder.endEntityInteraction();
        }
    }

    @WrapMethod(method = "handleAttack")
    private void kansokusha$attack(ServerboundAttackPacket packet, Operation<Void> original) {
        if (!player.level().getServer().isSameThread()) {
            original.call(packet);
            return;
        }
        NativeEntityRecorder.beginEntityInteraction(player, player.level().getEntityOrPart(packet.entityId()),
            InteractionHand.MAIN_HAND);
        try {
            original.call(packet);
        } finally {
            NativeEntityRecorder.endEntityInteraction();
        }
    }
}
