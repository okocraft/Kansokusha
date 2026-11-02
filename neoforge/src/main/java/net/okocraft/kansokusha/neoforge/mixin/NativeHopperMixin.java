package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.okocraft.kansokusha.neoforge.NativeContainerMoveRecorder;
import org.spongepowered.asm.mixin.Mixin;

/** Correlates vanilla hopper pushes and pulls with their one committed destination insertion. */
@Mixin(HopperBlockEntity.class)
abstract class NativeHopperMixin {
    @WrapMethod(method = "ejectItems")
    private static boolean kansokusha$eject(Level level, BlockPos position, HopperBlockEntity hopper,
                                             Operation<Boolean> original) {
        if (!(level instanceof ServerLevel serverLevel)) return original.call(level, position, hopper);
        var scope = NativeContainerMoveRecorder.beginPush(serverLevel, position, hopper);
        try {
            return original.call(level, position, hopper);
        } finally {
            NativeContainerMoveRecorder.end(scope);
        }
    }

    @WrapMethod(method = "suckInItems")
    private static boolean kansokusha$intake(Level level, Hopper hopper, Operation<Boolean> original) {
        if (!(level instanceof ServerLevel serverLevel)) return original.call(level, hopper);
        var scope = NativeContainerMoveRecorder.beginPull(serverLevel, hopper);
        try {
            return original.call(level, hopper);
        } finally {
            NativeContainerMoveRecorder.end(scope);
        }
    }

    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;")
    private static ItemStack kansokusha$move(Container from, Container to, ItemStack input, Direction side,
                                              Operation<ItemStack> original) {
        ItemStack before = input.copy();
        ItemStack remainder = original.call(from, to, input, side);
        NativeContainerMoveRecorder.moved(from, to, before, remainder);
        return remainder;
    }

    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
    private static boolean kansokusha$pickup(Container destination, ItemEntity entity, Operation<Boolean> original) {
        ItemStack before = entity.getItem().copy();
        boolean moved = original.call(destination, entity);
        NativeContainerMoveRecorder.pickedUp(destination, entity, before, moved);
        return moved;
    }
}
