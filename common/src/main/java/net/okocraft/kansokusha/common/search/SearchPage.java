package net.okocraft.kansokusha.common.search;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One keyset-paginated page of common event data.
 */
@ApiStatus.Internal
@NotNullByDefault
public record SearchPage(
    List<Event> events,
    Optional<SearchRequest.Cursor> nextCursor,
    Optional<SearchRequest.Cursor> previousCursor
) {

    public SearchPage {
        events = List.copyOf(events);
        Objects.requireNonNull(nextCursor, "nextCursor");
        Objects.requireNonNull(previousCursor, "previousCursor");
    }

    /**
     * Event fields needed by platform search rendering.
     *
     * <p>Searches only include payloads up to the storage search byte budget. Detail lookups
     * include the full payload. No decoding happens in the storage layer.</p>
     *
     * @param actorName the latest observed name of a player actor
     */
    public record Event(
        UUID eventId,
        Key eventType,
        Instant occurredAt,
        Optional<Key> server,
        Optional<Key> world,
        Optional<BlockPosition> position,
        Optional<EventActor> actor,
        Optional<String> actorName,
        Optional<Key> targetType,
        Optional<String> searchText,
        Optional<StoredPayload> payload
    ) {

        public Event {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(actorName, "actorName");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(searchText, "searchText");
            Objects.requireNonNull(payload, "payload");
        }

        public Event(
            UUID eventId,
            Key eventType,
            Instant occurredAt,
            Optional<Key> server,
            Optional<Key> world,
            Optional<BlockPosition> position,
            Optional<EventActor> actor,
            Optional<String> actorName,
            Optional<Key> targetType,
            Optional<String> searchText
        ) {
            this(eventId, eventType, occurredAt, server, world, position, actor, actorName,
                targetType, searchText, Optional.empty());
        }

        public SearchRequest.Cursor cursor(SearchRequest.Direction direction) {
            return new SearchRequest.Cursor(this.occurredAt, this.eventId, direction);
        }
    }
}
