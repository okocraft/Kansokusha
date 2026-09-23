package net.okocraft.kansokusha.paper.builtin;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PaperItemStackPayloadCodecTest {

    @Test
    void testItemStackRoundTripsThroughMinecraftNbt() {
        var itemStack = ItemStack.of(Material.DIAMOND, 3);

        var restored = PaperItemStackPayloadCodec.decode(
            PaperItemStackPayloadCodec.encode(itemStack)
        );

        Assertions.assertEquals(itemStack, restored);
        Assertions.assertNotSame(itemStack, restored);
    }

    @Test
    void testEncodedValueIsDetachedFromLiveItemStack() {
        var itemStack = ItemStack.of(Material.EMERALD, 2);

        var encoded = PaperItemStackPayloadCodec.encode(itemStack);
        itemStack.setAmount(7);

        var restored = PaperItemStackPayloadCodec.decode(encoded);
        Assertions.assertEquals(Material.EMERALD, restored.getType());
        Assertions.assertEquals(2, restored.getAmount());
    }

    @Test
    void testEmptyItemStackRoundTripsAsEmptyCompound() {
        var encoded = PaperItemStackPayloadCodec.encode(ItemStack.empty());

        Assertions.assertTrue(encoded.isEmpty());
        Assertions.assertTrue(PaperItemStackPayloadCodec.decode(encoded).isEmpty());
    }

    @Test
    void testNullValuesAreRejected() {
        Assertions.assertThrows(
            NullPointerException.class,
            () -> PaperItemStackPayloadCodec.encode(null)
        );
        Assertions.assertThrows(
            NullPointerException.class,
            () -> PaperItemStackPayloadCodec.decode(null)
        );
    }
}
