package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FlintAndSteelItem.class)
abstract class NativeFlintIgniteMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult kansokusha$ignite(UseOnContext context, Operation<InteractionResult> original) {
        var level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        var before = level.getBlockState(pos);
        var result = original.call(context);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && result.consumesAction() && level instanceof ServerLevel server
            && context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)
            recorder.recordFireChange(server, pos, before, server.getBlockState(pos), null, player);
        return result;
    }
}
