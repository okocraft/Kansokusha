package net.okocraft.kansokusha.common.search;

import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;

/**
 * One persisted event with the storage metadata shown by {@code /kansokusha event}.
 */
@ApiStatus.Internal
@NotNullByDefault
public record EventDetail(SearchPage.Event event, PayloadGeneration payloadGeneration, Instant expiresAt) {

    public EventDetail {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(payloadGeneration, "payloadGeneration");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
