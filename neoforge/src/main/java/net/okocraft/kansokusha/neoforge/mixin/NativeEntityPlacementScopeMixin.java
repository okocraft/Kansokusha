package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.okocraft.kansokusha.neoforge.NativeEntityRecorder;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayerGameMode.class)
public abstract class NativeEntityPlacementScopeMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult kansokusha$useOn(ServerPlayer player, Level level, ItemStack item,
                                               InteractionHand hand, BlockHitResult hit,
                                               Operation<InteractionResult> original) {
        NativeEntityRecorder.beginPlacementUse(player, item, hand, hit.getBlockPos());
        InteractionResult result = InteractionResult.PASS;
        try {
            result = original.call(player, level, item, hand, hit);
            return result;
        } finally {
            NativeEntityRecorder.endPlacementUse(result);
        }
    }

    @WrapMethod(method = "useItem")
    private InteractionResult kansokusha$use(ServerPlayer player, Level level, ItemStack item,
                                             InteractionHand hand, Operation<InteractionResult> original) {
        NativeEntityRecorder.beginPlacementUse(player, item, hand);
        InteractionResult result = InteractionResult.PASS;
        try {
            result = original.call(player, level, item, hand);
            return result;
        } finally {
            NativeEntityRecorder.endPlacementUse(result);
        }
    }
}
