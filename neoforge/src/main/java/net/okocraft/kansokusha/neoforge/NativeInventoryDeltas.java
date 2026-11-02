package net.okocraft.kansokusha.neoforge;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Match container changes to the opposite player and cursor change in one click. */
final class NativeInventoryDeltas {
    private NativeInventoryDeltas() { }

    static List<Delta> transfers(List<ItemStack> containerBefore, List<ItemStack> containerAfter,
                                 List<ItemStack> playerBefore, List<ItemStack> playerAfter) {
        var container = diff(containerBefore, containerAfter);
        var player = diff(playerBefore, playerAfter);
        var transfers = new ArrayList<Delta>();
        for (var change : container) {
            if (change.amount == 0) continue;
            for (var counterpart : player) {
                if (ItemStack.isSameItemSameComponents(change.item, counterpart.item)
                    && committedAmount(change.amount, counterpart.amount) != 0) {
                    transfers.add(new Delta(change.item, committedAmount(change.amount, counterpart.amount)));
                    break;
                }
            }
        }
        return transfers;
    }

    static int committedAmount(int containerDelta, int playerOrWorldDelta) {
        return containerDelta != 0 && containerDelta == -playerOrWorldDelta ? containerDelta : 0;
    }

    private static List<MutableDelta> diff(List<ItemStack> before, List<ItemStack> after) {
        var changes = new ArrayList<MutableDelta>();
        for (var stack : before) merge(changes, stack, -1);
        for (var stack : after) merge(changes, stack, 1);
        return changes;
    }

    private static void merge(List<MutableDelta> changes, ItemStack stack, int sign) {
        if (stack.isEmpty()) return;
        for (var change : changes) {
            if (ItemStack.isSameItemSameComponents(change.item, stack)) {
                change.amount += sign * stack.getCount();
                return;
            }
        }
        changes.add(new MutableDelta(stack.copyWithCount(1), sign * stack.getCount()));
    }

    record Delta(ItemStack item, int amount) { }

    private static final class MutableDelta {
        private final ItemStack item;
        private int amount;
        private MutableDelta(ItemStack item, int amount) { this.item = item; this.amount = amount; }
    }
}
