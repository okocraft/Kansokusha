package net.okocraft.kansokusha.velocity.builtin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VelocityCommunicationPayloadCodecTest {

    @Test
    void testChatRoundTripsOriginalJavaStringExactly() throws Exception {
        var original = "hello\u0000日本語\ud800tail";

        Assertions.assertEquals(
            original,
            VelocityCommunicationPayloadCodec.decodeChat(
                VelocityCommunicationPayloadCodec.encodeChat(original)
            )
        );
    }

    @Test
    void testCommandRoundTripsDescriptorAndOriginalCommandExactly() throws Exception {
        var payload = VelocityCommunicationPayloadCodec.encodeCommand(
            "api",
            "source\u0000name",
            "  command 日本語 \udc00"
        );

        Assertions.assertEquals(
            new VelocityCommunicationPayloadCodec.CommandPayload(
                "api",
                "source\u0000name",
                "  command 日本語 \udc00"
            ),
            VelocityCommunicationPayloadCodec.decodeCommand(payload)
        );
    }
}
