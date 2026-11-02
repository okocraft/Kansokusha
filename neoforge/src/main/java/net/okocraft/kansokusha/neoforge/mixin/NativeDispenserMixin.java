package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

import java.util.ArrayList;
import java.util.List;

/** Compares the dispenser inventory around the actual dispense call. */
@Mixin({DispenserBlock.class, DropperBlock.class})
abstract class NativeDispenserMixin {
    @WrapMethod(method = "dispenseFrom")
    private void kansokusha$dispense(ServerLevel level, BlockState state, BlockPos position,
                                     Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null || !(level.getBlockEntity(position) instanceof DispenserBlockEntity dispenser)) {
            original.call(level, state, position);
            return;
        }
        var before = snapshot(dispenser);
        original.call(level, state, position);
        var after = snapshot(dispenser);
        recorder.recordInventoryDiff("dispenser_dispense", level, position,
            new BlockActor(Key.key(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())), before, after, true);
    }

    private static List<ItemStack> snapshot(DispenserBlockEntity dispenser) {
        var result = new ArrayList<ItemStack>();
        for (int slot = 0; slot < dispenser.getContainerSize(); slot++) result.add(dispenser.getItem(slot).copy());
        return result;
    }
}
