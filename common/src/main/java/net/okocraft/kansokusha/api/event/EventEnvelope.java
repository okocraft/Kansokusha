package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

@NotNullByDefault
public record EventEnvelope(EventSubmission submission, Key retentionReference) {

    public EventEnvelope {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(retentionReference, "retentionReference");
    }
}
