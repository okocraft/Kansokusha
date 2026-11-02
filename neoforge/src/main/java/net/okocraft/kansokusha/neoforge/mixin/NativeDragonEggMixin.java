package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DragonEggBlock.class)
abstract class NativeDragonEggMixin {
    @WrapMethod(method = "teleport")
    private void kansokusha$teleport(BlockState state, Level level, BlockPos pos,
                                     Operation<Void> original) {
        var scope = level instanceof ServerLevel server
            ? NativeBlockOperationCapture.natural(server, "dragon_egg") : null;
        boolean completed = false;
        try {
            original.call(state, level, pos);
            completed = true;
        } finally {
            NativeBlockOperationCapture.end(scope, completed);
        }
    }
}
