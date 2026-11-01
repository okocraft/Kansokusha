package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;

/**
 * A half-open range {@code [from, to)} of {@code occurred_at}.
 */
@NotNullByDefault
public record OccurredRange(Instant from, Instant to) {

    public OccurredRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to: " + from + " / " + to);
        }
    }
}
