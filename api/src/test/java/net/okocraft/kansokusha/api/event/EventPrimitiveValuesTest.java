package net.okocraft.kansokusha.api.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventPrimitiveValuesTest {

    @Test
    void testPayloadGenerationAcceptsPositiveRangeAndStartsAtOne() {
        assertEquals(1, PayloadGeneration.FIRST.value());
        assertEquals(Integer.MAX_VALUE, new PayloadGeneration(Integer.MAX_VALUE).value());
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> new PayloadGeneration(0)),
            () -> assertThrows(IllegalArgumentException.class, () -> new PayloadGeneration(-1))
        );
    }

    @Test
    void testBlockPositionPreservesIntegerCoordinateBoundaries() {
        BlockPosition minimum = new BlockPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        BlockPosition maximum = new BlockPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertAll(
            () -> assertEquals(Integer.MIN_VALUE, minimum.x()),
            () -> assertEquals(Integer.MIN_VALUE, minimum.y()),
            () -> assertEquals(Integer.MIN_VALUE, minimum.z()),
            () -> assertEquals(Integer.MAX_VALUE, maximum.x()),
            () -> assertEquals(Integer.MAX_VALUE, maximum.y()),
            () -> assertEquals(Integer.MAX_VALUE, maximum.z())
        );
    }
}
