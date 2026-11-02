package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FlowingFluid.class)
abstract class NativeFluidSpreadMixin {
    @WrapMethod(method = "spreadTo")
    private void kansokusha$spread(LevelAccessor level, BlockPos position, BlockState state,
                                   Direction direction, FluidState target, Operation<Void> original) {
        original.call(level, position, state, direction, target);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && level instanceof ServerLevel server
            && (target.is(FluidTags.WATER) || target.is(FluidTags.LAVA)))
            recorder.recordFluidArrival(server, position, position.relative(direction.getOpposite()),
                state, server.getBlockState(position), direction);
    }
}
