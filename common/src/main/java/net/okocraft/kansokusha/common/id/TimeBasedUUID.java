package net.okocraft.kansokusha.common.id;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A class for generating {@link UUID}s based on UUID v7 (RFC 9562)
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
                next = (now << 12);
            } else {
                // 同一ミリ秒内、あるいは時計が逆行した場合
                if (lastSequence < 0xFFF) {
                    next = last + 1;
                } else {
                    // 同一ミリ秒内でシーケンスが溢れた場合（理論上稀だが、1ミリ秒待機してリトライ）
                    // 実際には 4096 ID/ms なので、超える可能性はある
                    while (now <= lastMillis) {
                        now = System.currentTimeMillis();
                    }
                    next = (now << 12);
                }
            }

            if (LAST_V7_TIMESTAMP.compareAndSet(last, next)) {
                long msb = ((next >>> 12) << 16) | 0x7000 | (next & 0xFFF);
                long lsb = (ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
                return new UUID(msb, lsb);
            }
        }
    }

    public static long getTimestamp(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUID v7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    private TimeBasedUUID() {
        throw new UnsupportedOperationException();
    }

}
