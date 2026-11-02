package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CaveVines.class)
interface NativeCaveVineHarvestMixin {
    @WrapMethod(method = "use")
    private static InteractionResult kansokusha$glowBerry(Entity entity, BlockState state,
                                                            Level level, BlockPos pos,
                                                            Operation<InteractionResult> original) {
        var result = original.call(entity, state, level, pos);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && result.consumesAction() && level instanceof ServerLevel server
            && entity instanceof ServerPlayer player)
            recorder.recordBlockHarvest(server, pos, player, state, server.getBlockState(pos), "glow_berry");
        return result;
    }
}
