package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Block.class)
abstract class NativeSupportLossMixin {
    @WrapMethod(method = "updateOrDestroy(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;II)V")
    private static void kansokusha$supportLoss(BlockState before, BlockState after, LevelAccessor world,
                                               BlockPos position, int flags, int updateLimit,
                                               Operation<Void> original) {
        if (!(world instanceof ServerLevel level) || !after.isAir() || before.isAir()
            || before.canSurvive(world, position)) {
            original.call(before, after, world, position, flags, updateLimit);
            return;
        }
        var scope = NativeBlockOperationCapture.natural(level, "support_loss");
        boolean completed = false;
        try {
            original.call(before, after, world, position, flags, updateLimit);
            completed = true;
        } finally {
            NativeBlockOperationCapture.end(scope, completed);
        }
    }
}
