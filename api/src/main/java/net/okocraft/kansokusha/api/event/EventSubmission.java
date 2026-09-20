package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

@NotNullByDefault
public record EventSubmission(
    Key eventType,
    PayloadGeneration payloadGeneration,
    Instant occurredAt,
    Key serverKey,
    @Nullable Key worldKey,
    @Nullable BlockPosition position,
    @Nullable String subjectReference,
    EventPayload payload
) {

    public EventSubmission {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadGeneration, "payloadGeneration");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(serverKey, "serverKey");
        requireNullableNonBlank(subjectReference, "subjectReference");
        Objects.requireNonNull(payload, "payload");

        if (position != null && worldKey == null) {
            throw new IllegalArgumentException("worldKey is required when position is present");
        }
    }

    private static void requireNullableNonBlank(@Nullable String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
