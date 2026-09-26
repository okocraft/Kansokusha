package net.okocraft.kansokusha.common.id;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Generates time-ordered UUID v7 values as defined by RFC 9562.
 */
public final class TimeBasedUUID {

    private static final Generator DEFAULT_GENERATOR = new Generator(
        System::currentTimeMillis,
        () -> ThreadLocalRandom.current().nextLong()
    );

    public static UUID generate() {
        return DEFAULT_GENERATOR.generate();
    }

    static final class Generator {

        private static final long RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL;

        private final LongSupplier clock;
        private final LongSupplier random;

        private long lastMillis = -1;
        private int randA;
        private long randB;

        Generator(LongSupplier clock, LongSupplier random) {
            this.clock = clock;
            this.random = random;
        }

        synchronized UUID generate() {
            var now = this.clock.getAsLong();
            if (now > this.lastMillis) {
                this.lastMillis = now;
                this.randA = (int) (this.random.getAsLong() & 0xFFF);
                this.randB = this.random.getAsLong() & RAND_B_MASK;
            } else {
                this.incrementRandom();
            }

            long msb = (this.lastMillis << 16) | 0x7000L | this.randA;
            long lsb = 0x8000000000000000L | this.randB;
            return new UUID(msb, lsb);
        }

        private void incrementRandom() {
            if (this.randB < RAND_B_MASK) {
                this.randB++;
                return;
            }

            this.randB = 0;
            if (this.randA < 0xFFF) {
                this.randA++;
                return;
            }

            // Exhausting all 74 counter bits in one logical millisecond is not realistic, but
            // advancing the logical timestamp is still safer than waiting for the wall clock.
            this.randA = 0;
            this.lastMillis++;
        }
    }

    private TimeBasedUUID() {
        throw new UnsupportedOperationException();
    }
}
