package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Records the filtered book edit once the asynchronous server-side update has committed. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class NativeBookEditMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "updateBookContents")
    private void kansokusha$edit(List<FilteredText> contents, int slot, Operation<Void> original) {
        var before = player.getInventory().getItem(slot).copy();
        original.call(contents, slot);
        record(slot, before);
    }

    @WrapMethod(method = "signBook")
    private void kansokusha$sign(FilteredText title, List<FilteredText> contents, int slot,
                                Operation<Void> original) {
        var before = player.getInventory().getItem(slot).copy();
        original.call(title, contents, slot);
        record(slot, before);
    }

    private void record(int slot, ItemStack before) {
        var after = player.getInventory().getItem(slot);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && !ItemStack.isSameItemSameComponents(before, after))
            recorder.recordBookEdit(player, before, after);
    }
}
