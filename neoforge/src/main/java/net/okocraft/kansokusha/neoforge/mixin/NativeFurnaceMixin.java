package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

/** Records output produced by a completed vanilla furnace, smoker, or blast furnace tick. */
@Mixin(AbstractFurnaceBlockEntity.class)
abstract class NativeFurnaceMixin {
    @WrapMethod(method = "serverTick")
    private static void kansokusha$process(ServerLevel level, BlockPos pos, BlockState state,
                                           AbstractFurnaceBlockEntity entity, Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null) {
            original.call(level, pos, state, entity);
            return;
        }
        ItemStack input = entity.getItem(0).copy();
        ItemStack before = entity.getItem(2).copy();
        original.call(level, pos, state, entity);
        ItemStack after = entity.getItem(2);
        if (after.isEmpty()) return;
        int produced = ItemStack.isSameItemSameComponents(before, after)
            ? after.getCount() - before.getCount() : after.getCount();
        if (produced > 0) recorder.recordFurnaceProcess(level, pos, input, after, produced);
    }
}
