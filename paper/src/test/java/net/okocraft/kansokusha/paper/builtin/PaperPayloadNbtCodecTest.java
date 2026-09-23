package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PaperPayloadNbtCodecTest {

    @Test
    void testCompoundRoundTripUsesDetachedPayloadBytes() throws Exception {
        var tag = new CompoundTag();
        tag.putString("value", "captured");

        var payload = PaperPayloadNbtCodec.encode(tag);
        tag.putString("value", "mutated");

        var expected = new CompoundTag();
        expected.putString("value", "captured");
        Assertions.assertEquals(expected, PaperPayloadNbtCodec.decode(payload));
    }

    @Test
    void testEmptyCompoundRoundTrips() throws Exception {
        var decoded = PaperPayloadNbtCodec.decode(
            PaperPayloadNbtCodec.encode(new CompoundTag())
        );

        Assertions.assertTrue(decoded.isEmpty());
    }

    @Test
    void testNullValuesAreRejected() {
        Assertions.assertThrows(
            NullPointerException.class,
            () -> PaperPayloadNbtCodec.encode(null)
        );
        Assertions.assertThrows(
            NullPointerException.class,
            () -> PaperPayloadNbtCodec.decode(null)
        );
    }
}
