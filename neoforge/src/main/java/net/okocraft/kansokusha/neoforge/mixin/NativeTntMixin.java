package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TntBlock.class)
abstract class NativeTntMixin {
    @WrapMethod(method = "prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)Z")
    private static boolean kansokusha$prime(Level level, BlockPos pos, LivingEntity igniter,
                                             Operation<Boolean> original) {
        var primed = original.call(level, pos, igniter);
        var recorder = KansokushaNeoForge.recorder();
        if (primed && recorder != null && level instanceof ServerLevel server)
            recorder.recordTntPrime(server, pos, igniter);
        return primed;
    }
}
