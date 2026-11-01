package net.okocraft.kansokusha.paper.builtin;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Groups items by {@link ItemStack#isSimilar(ItemStack)} so that they can be looked up in a hash map.
 * The hash uses the type and the item meta, which {@code isSimilar} compares, but not the amount.
 */
@NotNullByDefault
final class SimilarItem {

    private final ItemStack item;
    private final int hash;

    SimilarItem(ItemStack item) {
        this.item = item;
        this.hash = 31 * item.getType().hashCode() + (item.hasItemMeta() ? item.getItemMeta().hashCode() : 0);
    }

    ItemStack item() {
        return this.item;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SimilarItem similar && this.item.isSimilar(similar.item);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
