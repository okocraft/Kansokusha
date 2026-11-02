package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({IceBlock.class, LeavesBlock.class, SnowLayerBlock.class})
abstract class NativeNaturalFadeMixin {
    @WrapMethod(method = "randomTick")
    private void kansokusha$fade(BlockState state, ServerLevel level, BlockPos pos,
                                 RandomSource random, Operation<Void> original) {
        original.call(state, level, pos, random);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null)
            recorder.recordNaturalChange(level, pos, state, level.getBlockState(pos), "fade", null);
    }
}
