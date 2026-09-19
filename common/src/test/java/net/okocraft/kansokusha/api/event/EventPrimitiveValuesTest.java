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
    void testEventPositionPreservesFiniteCoordinates() {
        EventPosition position = new EventPosition(-12.5, 0.0, Double.MAX_VALUE);

        assertEquals(-12.5, position.x());
        assertEquals(0.0, position.y());
        assertEquals(Double.MAX_VALUE, position.z());
    }

    @Test
    void testEventPositionRejectsNonFiniteCoordinates() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                () -> new EventPosition(Double.NaN, 0.0, 0.0)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new EventPosition(0.0, Double.POSITIVE_INFINITY, 0.0)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new EventPosition(0.0, 0.0, Double.NEGATIVE_INFINITY))
        );
    }
}
