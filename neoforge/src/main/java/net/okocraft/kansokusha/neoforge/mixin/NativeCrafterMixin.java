package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CrafterBlock.class)
abstract class NativeCrafterMixin {
    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/CrafterBlock;dispenseItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/CrafterBlockEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/item/crafting/RecipeHolder;)V",
        ordinal = 0))
    private void kansokusha$craft(CrafterBlock block, ServerLevel level, BlockPos pos,
                                   CrafterBlockEntity crafter, ItemStack result, BlockState state,
                                   RecipeHolder<?> recipe, Operation<Void> original) {
        original.call(block, level, pos, crafter, result, state, recipe);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && !result.isEmpty()) recorder.recordCrafterProcess(level, pos, crafter, result);
    }
}
