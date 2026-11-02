package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

/** Confirms that smithing consumed its template, base, and addition before logging output. */
@Mixin(SmithingMenu.class)
abstract class NativeSmithingMixin {
    @WrapMethod(method = "onTake")
    private void kansokusha$smith(Player player, ItemStack result, Operation<Void> original) {
        var menu = (SmithingMenu) (Object) this;
        var beforeTemplate = menu.getSlot(0).getItem().copy();
        var beforeBase = menu.getSlot(1).getItem().copy();
        var beforeAddition = menu.getSlot(2).getItem().copy();
        original.call(player, result);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && player instanceof ServerPlayer serverPlayer && !result.isEmpty()
            && consumed(beforeTemplate, menu.getSlot(0).getItem())
            && consumed(beforeBase, menu.getSlot(1).getItem())
            && consumed(beforeAddition, menu.getSlot(2).getItem()))
            recorder.recordSmith(serverPlayer, result);
    }

    private static boolean consumed(ItemStack before, ItemStack after) {
        return !before.isEmpty() && (after.isEmpty() || !ItemStack.isSameItemSameComponents(before, after)
            || after.getCount() < before.getCount());
    }
}
