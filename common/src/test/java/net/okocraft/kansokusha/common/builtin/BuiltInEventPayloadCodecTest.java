package net.okocraft.kansokusha.common.builtin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BuiltInEventPayloadCodecTest {

    @Test
    void testBlockBreakRoundTrip() throws Exception {
        var payload = BuiltInEventPayloadCodec.encodeBlockBreak(
            "minecraft:oak_stairs[facing=north,half=bottom]"
        );

        var decoded = BuiltInEventPayloadCodec.decodeBlockBreak(payload);

        Assertions.assertEquals(
            "minecraft:oak_stairs[facing=north,half=bottom]",
            decoded.blockData()
        );
    }

    @Test
    void testBlockPlaceRoundTrip() throws Exception {
        var payload = BuiltInEventPayloadCodec.encodeBlockPlace(
            "minecraft:water[level=0]",
            "minecraft:oak_log[axis=y]"
        );

        var decoded = BuiltInEventPayloadCodec.decodeBlockPlace(payload);

        Assertions.assertEquals("minecraft:water[level=0]", decoded.replacedBlockData());
        Assertions.assertEquals("minecraft:oak_log[axis=y]", decoded.placedBlockData());
    }

    @Test
    void testServerConnectedNullAndEmptyRemainDistinct() throws Exception {
        var nullPayload = BuiltInEventPayloadCodec.encodeServerConnected(null);
        var emptyPayload = BuiltInEventPayloadCodec.encodeServerConnected("");

        Assertions.assertNull(
            BuiltInEventPayloadCodec.decodeServerConnected(nullPayload).previousServerKey()
        );
        Assertions.assertEquals(
            "",
            BuiltInEventPayloadCodec.decodeServerConnected(emptyPayload).previousServerKey()
        );
        Assertions.assertNotEquals(nullPayload, emptyPayload);
    }

    @Test
    void testUtf8ByteLengthUsesBigEndianInt() throws Exception {
        var payload = BuiltInEventPayloadCodec.encodeBlockBreak("é");
        var bytes = payload.copyBytes();

        Assertions.assertArrayEquals(
            new byte[]{0, 0, 0, 2, (byte) 0xc3, (byte) 0xa9},
            bytes
        );
        Assertions.assertEquals(
            "é",
            BuiltInEventPayloadCodec.decodeBlockBreak(payload).blockData()
        );
    }
}
