package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TreeGrower.class)
abstract class NativeTreeGrowthMixin {
    @WrapMethod(method = "growTree")
    private boolean kansokusha$grow(ServerLevel level, ChunkGenerator generator, BlockPos pos,
                                    BlockState state, RandomSource random, Operation<Boolean> original) {
        var scope = NativeBlockOperationCapture.natural(level, "tree_growth");
        boolean grown = false;
        try {
            grown = original.call(level, generator, pos, state, random);
            return grown;
        } finally {
            NativeBlockOperationCapture.end(scope, grown);
        }
    }
}
