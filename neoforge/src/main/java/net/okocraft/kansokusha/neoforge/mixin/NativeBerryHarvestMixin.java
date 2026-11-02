package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SweetBerryBushBlock.class)
abstract class NativeBerryHarvestMixin {
    @WrapMethod(method = "useWithoutItem")
    private InteractionResult kansokusha$berry(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit,
                                                Operation<InteractionResult> original) {
        var result = original.call(state, level, pos, player, hit);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && result.consumesAction() && level instanceof ServerLevel server
            && player instanceof ServerPlayer actor)
            recorder.recordBlockHarvest(server, pos, actor, state, server.getBlockState(pos), "sweet_berry");
        return result;
    }
}
