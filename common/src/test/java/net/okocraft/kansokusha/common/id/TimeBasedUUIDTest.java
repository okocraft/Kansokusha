package net.okocraft.kansokusha.common.id;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;
import java.util.function.LongSupplier;

class TimeBasedUUIDTest {

    @Test
    void testGenerateProducesUniqueUuidV7Values() {
        var count = 10_000;
        var uuids = new HashSet<UUID>(count);

        for (var index = 0; index < count; index++) {
            var uuid = TimeBasedUUID.generate();
            Assertions.assertEquals(7, uuid.version());
            Assertions.assertEquals(2, uuid.variant());
            Assertions.assertTrue(uuids.add(uuid), "Duplicate UUID: " + uuid);
        }
    }

    @Test
    void testClockRollbackDoesNotWaitForWallClockToCatchUp() {
        var count = 5_000;
        var clock = new RollbackClock(10_000L, 9_000L, count);
        var generator = new TimeBasedUUID.Generator(clock, () -> 0L);
        var uuids = new HashSet<UUID>(count);
        UUID previous = null;

        for (var index = 0; index < count; index++) {
            var uuid = generator.generate();
            Assertions.assertEquals(7, uuid.version());
            Assertions.assertTrue(uuids.add(uuid), "Duplicate UUID: " + uuid);
            if (previous != null) {
                Assertions.assertTrue(previous.compareTo(uuid) < 0);
            }
            previous = uuid;
        }

        Assertions.assertEquals(count, clock.calls());
    }

    @Test
    void testCounterOverflowAdvancesLogicalTimestampWithoutWaiting() {
        var clock = new RollbackClock(10_000L, 9_000L, 2);
        var generator = new TimeBasedUUID.Generator(clock, () -> -1L);

        var first = generator.generate();
        var second = generator.generate();

        Assertions.assertEquals(10_000L, timestamp(first));
        Assertions.assertEquals(10_001L, timestamp(second));
        Assertions.assertTrue(first.compareTo(second) < 0);
        Assertions.assertEquals(2, clock.calls());
    }

    private static long timestamp(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }

    private static final class RollbackClock implements LongSupplier {

        private final long first;
        private final long rolledBack;
        private final int maximumCalls;
        private int calls;

        private RollbackClock(long first, long rolledBack, int maximumCalls) {
            this.first = first;
            this.rolledBack = rolledBack;
            this.maximumCalls = maximumCalls;
        }

        @Override
        public long getAsLong() {
            this.calls++;
            if (this.calls > this.maximumCalls) {
                throw new AssertionError("UUID generation polled the wall clock while waiting for it to advance.");
            }
            return this.calls == 1 ? this.first : this.rolledBack;
        }

        private int calls() {
            return this.calls;
        }
    }
}
