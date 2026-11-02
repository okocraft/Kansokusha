package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

import java.util.HashMap;

@Mixin(FireBlock.class)
abstract class NativeFireSpreadMixin {
    @WrapMethod(method = "tick")
    private void kansokusha$spread(BlockState state, ServerLevel level, BlockPos pos,
                                   RandomSource random, Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null) {
            original.call(state, level, pos, random);
            return;
        }
        var nearbyAir = new HashMap<BlockPos, BlockState>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            for (int dy = -1; dy <= 4; dy++) {
                var target = pos.offset(dx, dy, dz);
                var before = level.getBlockState(target);
                if (before.isAir()) nearbyAir.put(target, before);
            }
        original.call(state, level, pos, random);
        nearbyAir.forEach((target, before) -> {
            var after = level.getBlockState(target);
            if (after.is(Blocks.FIRE) || after.is(Blocks.SOUL_FIRE))
                recorder.recordNaturalChange(level, target, before, after, "fire_spread", pos);
        });
    }
}
