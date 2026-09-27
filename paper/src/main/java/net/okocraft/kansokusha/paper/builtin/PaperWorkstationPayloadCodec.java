package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.okocraft.kansokusha.api.event.EventPayload;
import org.bukkit.Keyed;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@NotNullByDefault
final class PaperWorkstationPayloadCodec {

    private PaperWorkstationPayloadCodec() {
    }

    static EventPayload encodeCraft(
        Recipe recipe,
        ItemStack[] matrix,
        @Nullable ItemStack result,
        String click,
        String action
    ) {
        var payload = new CompoundTag();
        putRecipe(payload, recipe);
        payload.put("input_items", PaperContainerPayloadCodec.snapshotItems(matrix));
        payload.put("result_item", PaperContainerPayloadCodec.snapshotItem(result));
        payload.putString("click", click.toLowerCase(Locale.ROOT));
        payload.putString("action", action.toLowerCase(Locale.ROOT));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeAnvil(
        @Nullable ItemStack first,
        @Nullable ItemStack second,
        @Nullable ItemStack result,
        @Nullable String renameText,
        int repairItemCount,
        int repairCost,
        String click,
        String action
    ) {
        var payload = new CompoundTag();
        var inputs = new ListTag();
        inputs.add(PaperContainerPayloadCodec.snapshotItem(first));
        inputs.add(PaperContainerPayloadCodec.snapshotItem(second));
        payload.put("input_items", inputs);
        payload.put("result_item", PaperContainerPayloadCodec.snapshotItem(result));
        if (renameText != null) {
            payload.putString("rename_text", renameText);
        }
        payload.putInt("repair_item_count", repairItemCount);
        payload.putInt("repair_cost", repairCost);
        payload.putString("click", click.toLowerCase(Locale.ROOT));
        payload.putString("action", action.toLowerCase(Locale.ROOT));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeSmith(
        @Nullable Recipe recipe,
        @Nullable ItemStack template,
        @Nullable ItemStack equipment,
        @Nullable ItemStack mineral,
        @Nullable ItemStack result,
        String click,
        String action
    ) {
        var payload = new CompoundTag();
        if (recipe != null) {
            putRecipe(payload, recipe);
        }
        var inputs = new ListTag();
        inputs.add(PaperContainerPayloadCodec.snapshotItem(template));
        inputs.add(PaperContainerPayloadCodec.snapshotItem(equipment));
        inputs.add(PaperContainerPayloadCodec.snapshotItem(mineral));
        payload.put("input_items", inputs);
        payload.put("result_item", PaperContainerPayloadCodec.snapshotItem(result));
        payload.putString("click", click.toLowerCase(Locale.ROOT));
        payload.putString("action", action.toLowerCase(Locale.ROOT));
        return PaperPayloadNbtCodec.encode(payload);
    }

    static EventPayload encodeEnchant(
        ItemStack item,
        int expLevelCost,
        int button,
        Map<Enchantment, Integer> enchantments
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(enchantments, "enchantments");

        var payload = new CompoundTag();
        payload.put("item", PaperContainerPayloadCodec.snapshotItem(item));
        payload.putInt("exp_level_cost", expLevelCost);
        payload.putInt("button", button);

        var encodedEnchantments = new ListTag();
        for (var entry : enchantments.entrySet()) {
            var encoded = new CompoundTag();
            encoded.putString("type", entry.getKey().getKey().toString());
            encoded.putInt("level", entry.getValue());
            encodedEnchantments.add(encoded);
        }
        payload.put("enchantments", encodedEnchantments);
        return PaperPayloadNbtCodec.encode(payload);
    }

    private static void putRecipe(CompoundTag payload, Recipe recipe) {
        if (recipe instanceof Keyed keyed) {
            payload.putString("recipe", keyed.getKey().toString());
        }
    }
}
