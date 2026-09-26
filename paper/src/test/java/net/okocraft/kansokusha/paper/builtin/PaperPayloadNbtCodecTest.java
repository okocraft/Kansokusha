package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;

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
    void testAllSupportedTagKindsRoundTrip() throws Exception {
        var tag = new CompoundTag();
        tag.putBoolean("forced", true);
        tag.putByte("byte_value", (byte) -7);
        tag.putShort("short_value", (short) -321);
        tag.putInt("x", -30_000_000);
        tag.putLong("transition_duration_ticks", 123_456_789_012L);
        tag.putFloat("yaw", 12.5F);
        tag.putDouble("z", -10.25D);
        tag.putByteArray("serialized", new byte[]{0, 1, 2, -1});
        tag.putString("type", "minecraft:oak_log");
        tag.putString(
            "item_entity_uuid",
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString()
        );
        tag.putIntArray("int_array", new int[]{0, 1, -1, 300});
        tag.putLongArray("long_array", new long[]{0, 1, -1, 100_000});

        var nested = new CompoundTag();
        nested.putString("source_kind", "entity");
        nested.putString("dynamic_property", "captured");
        tag.put("source", nested);

        var list = new ListTag();
        list.add(StringTag.valueOf("minecraft:stone"));
        list.add(IntTag.valueOf(42));
        tag.put("event_result_items", list);

        Assertions.assertEquals(
            tag,
            PaperPayloadNbtCodec.decode(PaperPayloadNbtCodec.encode(tag))
        );
    }

    @Test
    void testCompactEncodingRemovesMostNbtSchemaOverhead() throws Exception {
        var tag = new CompoundTag();
        tag.put(
            "pre_state",
            blockState("minecraft:oak_log", "axis", "x")
        );
        tag.put(
            "post_state",
            blockState("minecraft:stripped_oak_log", "axis", "x")
        );
        tag.putString("source_event", "block_spread");
        tag.put("source", PaperPayloadNbtCodec.position(
            new net.okocraft.kansokusha.api.position.BlockPosition(1234, 64, -5678)
        ));
        tag.putString(
            "item_entity_uuid",
            "123e4567-e89b-12d3-a456-426614174000"
        );

        var compact = PaperPayloadNbtCodec.encode(tag).copyBytes().length;
        var legacy = legacyNbtSize(tag);

        Assertions.assertTrue(
            compact * 100 <= legacy * 60,
            () -> "Expected at least 40% reduction, legacy=" + legacy + ", compact=" + compact
        );
    }

    @Test
    void testEmptyCompoundRoundTrips() throws Exception {
        var payload = PaperPayloadNbtCodec.encode(new CompoundTag());
        var decoded = PaperPayloadNbtCodec.decode(payload);

        Assertions.assertTrue(decoded.isEmpty());
        Assertions.assertEquals(1, payload.copyBytes().length);
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

    private static CompoundTag blockState(
        String name,
        String property,
        String value
    ) {
        var state = new CompoundTag();
        state.putString("Name", name);
        var properties = new CompoundTag();
        properties.putString(property, value);
        state.put("Properties", properties);
        return state;
    }

    private static int legacyNbtSize(CompoundTag tag) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        }
        return bytes.size();
    }
}
