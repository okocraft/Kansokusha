package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.context.UseOnContext;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ShearsItem.class)
abstract class NativeShearsHarvestMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult kansokusha$shear(UseOnContext context, Operation<InteractionResult> original) {
        var pos = context.getClickedPos();
        var before = context.getLevel().getBlockState(pos);
        var result = original.call(context);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && result.consumesAction() && context.getLevel() instanceof ServerLevel server
            && context.getPlayer() instanceof ServerPlayer player)
            recorder.recordBlockHarvest(server, pos, player, before, server.getBlockState(pos), "shears");
        return result;
    }
}
