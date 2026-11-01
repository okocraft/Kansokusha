package net.okocraft.kansokusha.common.search;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What the database holds for one event type.
 *
 * @param count  the number of persisted events
 * @param recent the number of persisted events that occurred within the recent window
 * @param latest when the newest persisted event occurred, if any
 */
@ApiStatus.Internal
@NotNullByDefault
public record EventTypeStatistics(long count, long recent, Optional<Instant> latest) {

    public EventTypeStatistics {
        Objects.requireNonNull(latest, "latest");
    }
}
