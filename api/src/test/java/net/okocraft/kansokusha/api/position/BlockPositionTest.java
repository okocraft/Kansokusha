package net.okocraft.kansokusha.api.position;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockPositionTest {

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
