package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.kyori.adventure.key.Key;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

/** Observes successful player teleports, including direct API calls and dimension transitions. */
@Mixin(ServerPlayer.class)
abstract class NativePlayerTeleportMixin {
    @WrapMethod(method = "teleport")
    private ServerPlayer kansokusha$transition(TeleportTransition transition, Operation<ServerPlayer> original) {
        var player = (ServerPlayer) (Object) this;
        var world = Key.key(player.level().dimension().identifier().toString());
        var from = player.position();
        var arrived = original.call(transition);
        var recorder = KansokushaNeoForge.recorder();
        if (arrived != null && recorder != null) recorder.recordTeleport(arrived, world, from);
        return arrived;
    }

    @WrapMethod(method = "teleportTo(DDD)V")
    private void kansokusha$direct(double x, double y, double z, Operation<Void> original) {
        var player = (ServerPlayer) (Object) this;
        var world = Key.key(player.level().dimension().identifier().toString());
        var from = player.position();
        original.call(x, y, z);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null) recorder.recordTeleport(player, world, from);
    }
}
