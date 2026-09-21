package net.okocraft.kansokusha.common.api;

import net.okocraft.kansokusha.api.event.EventSubmission;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Ownership-transfer boundary between the public API and asynchronous ingestion.
 */
@FunctionalInterface
@ApiStatus.Internal
@NotNullByDefault
public interface EventIntake {

    Admission accept(EventSubmission submission);

    enum Admission {
        ACCEPTED,
        UNAVAILABLE,
        CLOSED
    }
}
