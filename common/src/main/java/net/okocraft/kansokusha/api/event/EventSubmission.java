package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

@NotNullByDefault
public record EventSubmission(
    Key eventType,
    PayloadGeneration payloadGeneration,
    Instant occurredAt,
    String serverIdentifier,
    @Nullable String worldIdentifier,
    @Nullable EventPosition position,
    @Nullable String subjectReference,
    OpaquePayload payload
) {

    public EventSubmission {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadGeneration, "payloadGeneration");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireNonBlank(serverIdentifier, "serverIdentifier");
        requireNullableNonBlank(worldIdentifier, "worldIdentifier");
        requireNullableNonBlank(subjectReference, "subjectReference");
        Objects.requireNonNull(payload, "payload");

        if (position != null && worldIdentifier == null) {
            throw new IllegalArgumentException("worldIdentifier is required when position is present");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNullableNonBlank(@Nullable String value, String name) {
        if (value != null) {
            requireNonBlank(value, name);
        }
    }
}
