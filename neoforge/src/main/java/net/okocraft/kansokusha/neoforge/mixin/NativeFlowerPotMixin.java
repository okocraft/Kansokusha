package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FlowerPotBlock.class)
abstract class NativeFlowerPotMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult kansokusha$insert(ItemStack held, BlockState state, Level level,
                                                 BlockPos pos, Player player, InteractionHand hand,
                                                 BlockHitResult hit, Operation<InteractionResult> original) {
        var result = original.call(held, state, level, pos, player, hand, hit);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && result.consumesAction() && level instanceof ServerLevel server
            && player instanceof ServerPlayer actor && state.getBlock() instanceof FlowerPotBlock before
            && server.getBlockState(pos).getBlock() instanceof FlowerPotBlock after)
            recorder.recordFlowerPot(server, pos, actor, before, after, held);
        return result;
    }

    @WrapMethod(method = "useWithoutItem")
    private InteractionResult kansokusha$remove(BlockState state, Level level, BlockPos pos,
                                                 Player player, BlockHitResult hit,
                                                 Operation<InteractionResult> original) {
        var result = original.call(state, level, pos, player, hit);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && result.consumesAction() && level instanceof ServerLevel server
            && player instanceof ServerPlayer actor && state.getBlock() instanceof FlowerPotBlock before
            && server.getBlockState(pos).getBlock() instanceof FlowerPotBlock after)
            recorder.recordFlowerPot(server, pos, actor, before, after, ItemStack.EMPTY);
        return result;
    }
}
