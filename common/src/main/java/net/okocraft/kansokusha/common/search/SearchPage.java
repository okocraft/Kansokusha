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
     * Common event fields needed by platform search rendering.
     *
     * <p>Payload generation, expiry and raw payload are intentionally not exposed here.</p>
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
        Optional<String> searchText
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
        }

        public SearchRequest.Cursor cursor(SearchRequest.Direction direction) {
            return new SearchRequest.Cursor(this.occurredAt, this.eventId, direction);
        }
    }
}
