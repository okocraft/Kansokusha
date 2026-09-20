package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.EventSubject;
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
    @Nullable EventSubject subject,
    EventPayload payload
) {

    public EventSubmission {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadGeneration, "payloadGeneration");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(serverKey, "serverKey");
        Objects.requireNonNull(payload, "payload");

        if (position != null && worldKey == null) {
            throw new IllegalArgumentException("worldKey is required when position is present");
        }
    }
}
