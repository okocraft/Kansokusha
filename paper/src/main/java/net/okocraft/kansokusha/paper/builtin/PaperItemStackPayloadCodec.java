package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperItemStackPayloadCodec {

    private static final String SERIALIZED_ITEM_KEY = "serialized";

    private PaperItemStackPayloadCodec() {
    }

    public static CompoundTag encode(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        if (itemStack.isEmpty()) {
            return new CompoundTag();
        }

        var payloadValue = new CompoundTag();
        payloadValue.put(
            SERIALIZED_ITEM_KEY,
            new ByteArrayTag(itemStack.serializeAsBytes())
        );
        return payloadValue;
    }

    public static ItemStack decode(CompoundTag payloadValue) {
        Objects.requireNonNull(payloadValue, "payloadValue");
        if (payloadValue.isEmpty()) {
            return ItemStack.empty();
        }

        var serialized = payloadValue.get(SERIALIZED_ITEM_KEY);
        if (!(serialized instanceof ByteArrayTag bytes)) {
            throw new IllegalArgumentException(
                "Paper ItemStack payload is missing the '" + SERIALIZED_ITEM_KEY + "' byte array."
            );
        }
        return ItemStack.deserializeBytes(bytes.getAsByteArray().clone());
    }
}
