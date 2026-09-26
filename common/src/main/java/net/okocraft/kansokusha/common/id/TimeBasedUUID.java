package net.okocraft.kansokusha.common.id;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates time-ordered UUID v7 values as defined by RFC 9562.
 */
public final class TimeBasedUUID {

    private static final AtomicLong LAST_V7_TIMESTAMP = new AtomicLong();

    public static UUID generate() {
        long now = System.currentTimeMillis();

        while (true) {
            long last = LAST_V7_TIMESTAMP.get();
            long lastMillis = last >>> 12;
            long lastSequence = last & 0xFFF;

            long next;
            if (now > lastMillis) {
                next = now << 12;
            } else if (lastSequence < 0xFFF) {
                // Keep values ordered when multiple IDs are generated in one millisecond,
                // or when the wall clock moves backwards.
                next = last + 1;
            } else {
                do {
                    now = System.currentTimeMillis();
                } while (now <= lastMillis);
                next = now << 12;
            }

            if (LAST_V7_TIMESTAMP.compareAndSet(last, next)) {
                long msb = ((next >>> 12) << 16) | 0x7000 | (next & 0xFFF);
                long lsb = (ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL)
                    | 0x8000000000000000L;
                return new UUID(msb, lsb);
            }
        }
    }

    private TimeBasedUUID() {
        throw new UnsupportedOperationException();
    }
}
