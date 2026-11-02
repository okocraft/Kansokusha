package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.CommonHooks;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CommonHooks.class)
abstract class NativeBonemealPlacementMixin {
    @WrapMethod(method = "onPlaceItemIntoWorld")
    private static InteractionResult kansokusha$place(UseOnContext context,
                                                       Operation<InteractionResult> original) {
        if (!(context.getLevel() instanceof ServerLevel level)
            || !(context.getItemInHand().getItem() instanceof BoneMealItem))
            return original.call(context);
        var scope = NativeBlockOperationCapture.fertilize(level, context.getPlayer());
        boolean accepted = false;
        try {
            var result = original.call(context);
            accepted = result.consumesAction() && result != InteractionResult.FAIL;
            return result;
        } finally {
            NativeBlockOperationCapture.end(scope, accepted);
        }
    }
}
