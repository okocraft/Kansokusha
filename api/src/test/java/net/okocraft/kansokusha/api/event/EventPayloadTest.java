package net.okocraft.kansokusha.api.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventPayloadTest {

    @Test
    void testInputAndOutputBytesAreDefensivelyCopied() {
        byte[] input = {1, 2, 3};
        EventPayload payload = EventPayload.copyOf(input);
        input[0] = 9;

        byte[] output = payload.copyBytes();
        output[1] = 8;

        assertEquals(3, payload.size());
        assertArrayEquals(new byte[]{1, 2, 3}, payload.copyBytes());
        assertNotSame(input, payload.unsafeBytes());
    }

    @Test
    void testOwnedBytesAreNotCopied() {
        byte[] input = {1, 2, 3};
        EventPayload payload = EventPayload.takeOwnership(input);

        assertSame(input, payload.unsafeBytes());
    }

    @Test
    void testOpenStreamReadsPayloadWithoutExposingWritableBytes() throws Exception {
        EventPayload payload = EventPayload.copyOf(new byte[]{1, 2, 3});

        try (var input = payload.openStream()) {
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
        }
    }

    @Test
    void testEmptyPayloadAndNullInputAreHandledAtBoundary() {
        EventPayload empty = EventPayload.copyOf(new byte[0]);

        assertEquals(0, empty.size());
        assertArrayEquals(new byte[0], empty.copyBytes());
        assertThrows(NullPointerException.class, () -> EventPayload.copyOf(null));
        assertThrows(NullPointerException.class, () -> EventPayload.takeOwnership(null));
    }

    @Test
    void testPayloadEqualityUsesByteContent() {
        EventPayload first = EventPayload.copyOf(new byte[]{-1, 0, 1});
        EventPayload sameContent = EventPayload.copyOf(new byte[]{-1, 0, 1});
        EventPayload differentContent = EventPayload.copyOf(new byte[]{-1, 0, 2});

        assertEquals(first, sameContent);
        assertEquals(first.hashCode(), sameContent.hashCode());
        assertNotEquals(first, differentContent);
    }
}
