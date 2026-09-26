package net.okocraft.kansokusha.common.storage;

import net.okocraft.kansokusha.api.event.EventSubmission;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * An accepted event whose timestamps have already been converted to epoch milliseconds,
 * so that writing it cannot fail because of an individual event.
 */
@NotNullByDefault
public record QueuedEvent(
    EventSubmission submission,
    long occurredAtMillis,
    long expiresAtMillis,
    @Nullable PlayerNameObservation playerNameObservation
) {
}
