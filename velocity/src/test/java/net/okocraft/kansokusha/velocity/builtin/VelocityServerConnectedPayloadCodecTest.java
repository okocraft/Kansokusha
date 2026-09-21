package net.okocraft.kansokusha.velocity.builtin;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VelocityServerConnectedPayloadCodecTest {

    @Test
    void testNullPreviousServerUsesMinusOneLength() throws Exception {
        var payload = VelocityServerConnectedPayloadCodec.encode(null);

        Assertions.assertArrayEquals(
            new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
            payload.copyBytes()
        );
        Assertions.assertTrue(
            VelocityServerConnectedPayloadCodec.decode(payload).isEmpty()
        );
    }

    @Test
    void testPreviousServerRoundTripsCanonicalKey() throws Exception {
        var previous = Key.key(
            "kansokusha",
            "velocity-server/006c006f006200620079"
        );

        var decoded = VelocityServerConnectedPayloadCodec.decode(
            VelocityServerConnectedPayloadCodec.encode(previous)
        );

        Assertions.assertEquals(previous, decoded.orElseThrow());
    }
}
