package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BoneMealItem.class)
abstract class NativeBonemealMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult kansokusha$use(UseOnContext context, Operation<InteractionResult> original) {
        if (!(context.getLevel() instanceof ServerLevel level)) return original.call(context);
        var scope = NativeBlockOperationCapture.fertilize(level, context.getPlayer());
        boolean accepted = false;
        try {
            var result = original.call(context);
            accepted = result.consumesAction();
            return result;
        } finally {
            NativeBlockOperationCapture.end(scope, accepted);
        }
    }

    @WrapMethod(method = "applyBonemeal")
    private static boolean kansokusha$apply(ItemStack stack, Level level, BlockPos pos,
                                             Player player, Operation<Boolean> original) {
        if (!(level instanceof ServerLevel server)) return original.call(stack, level, pos, player);
        var scope = NativeBlockOperationCapture.fertilize(server, player);
        boolean applied = false;
        try {
            applied = original.call(stack, level, pos, player);
            return applied;
        } finally {
            NativeBlockOperationCapture.end(scope, applied);
        }
    }
}
