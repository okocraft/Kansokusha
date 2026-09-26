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
    @Nullable Key serverKey,
    @Nullable Key worldKey,
    @Nullable BlockPosition position,
    @Nullable EventSubject subject,
    EventPayload payload
) {

    public EventSubmission {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadGeneration, "payloadGeneration");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");

        if (worldKey != null && serverKey == null) {
            throw new IllegalArgumentException("serverKey is required when worldKey is present");
        }
        if (position != null && worldKey == null) {
            throw new IllegalArgumentException("worldKey is required when position is present");
        }
    }
}
