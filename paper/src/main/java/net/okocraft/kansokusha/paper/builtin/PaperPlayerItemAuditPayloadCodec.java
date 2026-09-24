package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.UUID;

@ApiStatus.Internal
@NotNullByDefault
final class PaperPlayerItemAuditPayloadCodec {

    private PaperPlayerItemAuditPayloadCodec() {
    }

    static EventPayload encodeItemEntityChange(
        UUID itemEntityId,
        CompoundTag stack,
        double x,
        double y,
        double z
    ) {
        return encodeItemEntityChange(itemEntityId, stack, x, y, z, null);
    }

    static EventPayload encodeItemEntityChange(
        UUID itemEntityId,
        CompoundTag stack,
        double x,
        double y,
        double z,
        Integer remaining
    ) {
        Objects.requireNonNull(itemEntityId, "itemEntityId");
        Objects.requireNonNull(stack, "stack");

        var payload = new CompoundTag();
        payload.putString("item_entity_uuid", itemEntityId.toString());
        payload.put("stack", stack.copy());

        var position = new CompoundTag();
        position.putDouble("x", x);
        position.putDouble("y", y);
        position.putDouble("z", z);
        payload.put("position", position);

        if (remaining != null) {
            payload.putInt("remaining", remaining);
        }
        return PaperPayloadNbtCodec.encode(payload);
    }

    static CompoundTag snapshotBookMeta(BookMeta meta, boolean signed) {
        Objects.requireNonNull(meta, "meta");
        var book = ItemStack.of(signed ? Material.WRITTEN_BOOK : Material.WRITABLE_BOOK, 1);
        if (!book.setItemMeta(meta.clone())) {
            throw new IllegalArgumentException("BookMeta could not be applied to a book ItemStack.");
        }
        return PaperItemStackPayloadCodec.encode(book);
    }

    static BookMeta restoreBookMeta(CompoundTag snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var item = PaperItemStackPayloadCodec.decode(snapshot);
        if (!(item.getItemMeta() instanceof BookMeta bookMeta)) {
            throw new IllegalArgumentException("BookMeta snapshot did not decode to a book.");
        }
        return bookMeta;
    }

    static EventPayload encodeBookEdit(
        int slot,
        CompoundTag previousBookMeta,
        CompoundTag newBookMeta,
        boolean signing
    ) {
        Objects.requireNonNull(previousBookMeta, "previousBookMeta");
        Objects.requireNonNull(newBookMeta, "newBookMeta");

        var payload = new CompoundTag();
        payload.putInt("slot", slot);
        payload.putBoolean("signing", signing);
        payload.put("previous_book_meta", previousBookMeta.copy());
        payload.put("new_book_meta", newBookMeta.copy());
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeLecternChange(
        String action,
        CompoundTag before,
        CompoundTag after
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        var payload = new CompoundTag();
        payload.putString("action", action);
        payload.put("before", before.copy());
        payload.put("after", after.copy());
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodePlayerTrade(
        String sourceEvent,
        Merchant merchant,
        MerchantRecipe recipe,
        boolean rewardingExperience,
        boolean increasingTradeUses
    ) {
        Objects.requireNonNull(sourceEvent, "sourceEvent");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(recipe, "recipe");

        var payload = new CompoundTag();
        payload.putString("source_event", sourceEvent);
        payload.put("merchant", encodeMerchant(merchant));
        payload.put("trade", encodeRecipe(recipe));
        payload.putBoolean("rewarding_experience", rewardingExperience);
        payload.putBoolean("increasing_trade_uses", increasingTradeUses);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static CompoundTag encodeMerchant(Merchant merchant) {
        var payload = new CompoundTag();
        if (merchant instanceof Entity entity) {
            payload.putString("kind", "entity");
            payload.putString("uuid", entity.getUniqueId().toString());
            payload.putString("type", entity.getType().name());
        } else {
            payload.putString("kind", "standalone");
        }
        return payload;
    }

    private static CompoundTag encodeRecipe(MerchantRecipe recipe) {
        var payload = new CompoundTag();
        payload.put("result", PaperAdditionalBuiltInPayloadCodec.snapshotItem(recipe.getResult()));

        var ingredients = new ListTag();
        for (var ingredient : recipe.getIngredients()) {
            ingredients.add(PaperAdditionalBuiltInPayloadCodec.snapshotItem(ingredient));
        }
        payload.put("ingredients", ingredients);

        var adjustedIngredient = recipe.getAdjustedIngredient1();
        if (adjustedIngredient != null) {
            payload.put(
                "adjusted_ingredient_1",
                PaperAdditionalBuiltInPayloadCodec.snapshotItem(adjustedIngredient)
            );
        }

        payload.putInt("uses", recipe.getUses());
        payload.putInt("max_uses", recipe.getMaxUses());
        payload.putBoolean("experience_reward", recipe.hasExperienceReward());
        payload.putInt("villager_experience", recipe.getVillagerExperience());
        payload.putFloat("price_multiplier", recipe.getPriceMultiplier());
        payload.putInt("demand", recipe.getDemand());
        payload.putInt("special_price", recipe.getSpecialPrice());
        payload.putBoolean("ignore_discounts", recipe.shouldIgnoreDiscounts());
        return payload;
    }
}
