package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BrewingStandBlockEntity.class)
abstract class NativeBrewingMixin {
    @WrapMethod(method = "serverTick")
    private static void kansokusha$brew(Level level, BlockPos pos, BlockState state,
                                        BrewingStandBlockEntity stand, Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null || !(level instanceof ServerLevel serverLevel)) {
            original.call(level, pos, state, stand);
            return;
        }
        var before = new ItemStack[3];
        for (int slot = 0; slot < 3; slot++) before[slot] = stand.getItem(slot).copy();
        var ingredient = stand.getItem(3).copy();
        original.call(level, pos, state, stand);
        for (int slot = 0; slot < 3; slot++) {
            var after = stand.getItem(slot);
            if (!after.isEmpty() && !ItemStack.isSameItemSameComponents(before[slot], after))
                recorder.recordBrewProcess(serverLevel, pos, before[slot], ingredient, after);
        }
    }
}
