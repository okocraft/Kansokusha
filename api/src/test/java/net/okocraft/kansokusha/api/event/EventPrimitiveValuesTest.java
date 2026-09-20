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
}
