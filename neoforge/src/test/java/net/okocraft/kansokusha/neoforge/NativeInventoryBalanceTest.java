package net.okocraft.kansokusha.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeInventoryBalanceTest {
    @Test
    void committedPickupHasOppositeContainerAndPlayerChanges() {
        assertEquals(-1, NativeInventoryDeltas.committedAmount(-1, 1));
    }

    @Test
    void visiblePageChangeHasNoMatchingPlayerChange() {
        assertEquals(0, NativeInventoryDeltas.committedAmount(12, 0));
    }

    @Test
    void canceledClickMakesNoChange() {
        assertEquals(0, NativeInventoryDeltas.committedAmount(0, 0));
    }

    @Test
    void unexpectedQuantityIsNotAttributedToPlayer() {
        assertEquals(0, NativeInventoryDeltas.committedAmount(-12, 1));
    }
}
