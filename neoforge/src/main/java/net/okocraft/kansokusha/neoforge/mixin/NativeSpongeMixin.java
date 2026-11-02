package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SpongeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SpongeBlock.class)
abstract class NativeSpongeMixin {
    @WrapMethod(method = "lambda$removeWaterBreadthFirstSearch$1")
    private static BlockPos.TraversalNodeStatus kansokusha$absorb(BlockPos sponge, Level level,
                                                                   BlockState spongeState, BlockPos candidate,
                                                                   Operation<BlockPos.TraversalNodeStatus> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null || !(level instanceof ServerLevel server))
            return original.call(sponge, level, spongeState, candidate);
        var before = server.getBlockState(candidate);
        var result = original.call(sponge, level, spongeState, candidate);
        var after = server.getBlockState(candidate);
        if (!before.equals(after)) recorder.recordSpongeAbsorb(server, sponge, candidate, before, after);
        return result;
    }
}
