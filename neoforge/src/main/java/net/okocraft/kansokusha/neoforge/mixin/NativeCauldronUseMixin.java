package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractCauldronBlock.class)
abstract class NativeCauldronUseMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult kansokusha$use(ItemStack item, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit,
                                              Operation<InteractionResult> original) {
        var result = original.call(item, state, level, pos, player, hand, hit);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && result.consumesAction() && level instanceof ServerLevel server)
            recorder.recordCauldronChange(server, pos, state, server.getBlockState(pos), "player", player);
        return result;
    }
}
