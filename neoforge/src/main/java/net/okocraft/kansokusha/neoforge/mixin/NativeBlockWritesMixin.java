package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
abstract class NativeBlockWritesMixin {
    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean kansokusha$scopedWrite(BlockPos position, BlockState state, int flags,
                                            int recursionLeft, Operation<Boolean> original) {
        NativeBlockOperationCapture.observe((Level) (Object) this, position);
        return original.call(position, state, flags, recursionLeft);
    }
}
