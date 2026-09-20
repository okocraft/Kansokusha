package net.okocraft.kansokusha.common.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;

@NotNullByDefault
public record AcceptedEvent(
    EventSubmission submission,
    Key retentionPolicyKey,
    Instant expiresAt
) {

    public AcceptedEvent {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(retentionPolicyKey, "retentionPolicyKey");
        Objects.requireNonNull(expiresAt, "expiresAt");

        if (expiresAt.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("expiresAt must have millisecond precision");
        }
    }
}
