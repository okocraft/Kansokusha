package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LayeredCauldronBlock.class)
abstract class NativeCauldronNaturalMixin {
    @WrapMethod(method = "handlePrecipitation")
    private void kansokusha$rain(BlockState state, Level level, BlockPos pos,
                                  Biome.Precipitation precipitation, Operation<Void> original) {
        original.call(state, level, pos, precipitation);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && level instanceof ServerLevel server)
            recorder.recordCauldronChange(server, pos, state, server.getBlockState(pos),
                "precipitation_" + precipitation.name().toLowerCase(java.util.Locale.ROOT), null);
    }

    @WrapMethod(method = "receiveStalactiteDrip")
    private void kansokusha$drip(BlockState state, Level level, BlockPos pos, Fluid fluid,
                                  Operation<Void> original) {
        original.call(state, level, pos, fluid);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && level instanceof ServerLevel server)
            recorder.recordCauldronChange(server, pos, state, server.getBlockState(pos), "dripstone", null);
    }

    @WrapMethod(method = "lambda$entityInside$0")
    private void kansokusha$entity(ServerLevel server, BlockPos pos, BlockState state,
                                    Level level, Entity entity, Operation<Void> original) {
        var before = server.getBlockState(pos);
        original.call(server, pos, state, level, entity);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null) recorder.recordCauldronChange(server, pos, before,
            server.getBlockState(pos), "entity_extinguish", entity);
    }
}
