package net.okocraft.kansokusha.api.event;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * An event to record.
 *
 * @param eventType         the registered event type
 * @param payloadGeneration the payload generation registered for the event type
 * @param occurredAt        when the event occurred
 * @param serverKey         the server the event occurred on
 * @param worldKey          the world the event occurred in; requires {@code serverKey}
 * @param position          the block position the event occurred at; requires {@code worldKey}
 * @param actor             who or what directly performed the event
 * @param targetType        the type of what the event acted on, such as {@code minecraft:stone}
 *                          for a broken block or {@code minecraft:diamond} for a dropped item
 * @param payload           the provider-defined payload
 */
@NotNullByDefault
public record EventSubmission(
    Key eventType,
    PayloadGeneration payloadGeneration,
    Instant occurredAt,
    @Nullable Key serverKey,
    @Nullable Key worldKey,
    @Nullable BlockPosition position,
    @Nullable EventActor actor,
    @Nullable Key targetType,
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
