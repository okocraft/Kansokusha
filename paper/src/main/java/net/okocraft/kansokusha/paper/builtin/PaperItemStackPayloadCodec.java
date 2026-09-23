package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperItemStackPayloadCodec {

    private PaperItemStackPayloadCodec() {
    }

    public static CompoundTag encode(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        if (itemStack.isEmpty()) {
            return new CompoundTag();
        }

        var encoded = net.minecraft.world.item.ItemStack.CODEC.encodeStart(
            CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE),
            CraftItemStack.asNMSCopy(itemStack)
        ).getOrThrow();

        if (!(encoded instanceof CompoundTag compound)) {
            throw new IllegalStateException(
                "Minecraft ItemStack codec returned a non-compound NBT value: "
                    + encoded.getClass().getName()
            );
        }
        return compound.copy();
    }

    public static ItemStack decode(CompoundTag payloadValue) {
        Objects.requireNonNull(payloadValue, "payloadValue");
        if (payloadValue.isEmpty()) {
            return ItemStack.empty();
        }

        var decoded = net.minecraft.world.item.ItemStack.CODEC.parse(
            CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE),
            payloadValue.copy()
        ).getOrThrow();
        return CraftItemStack.asBukkitCopy(decoded);
    }
}
