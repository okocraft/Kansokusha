package net.okocraft.kansokusha.common.search;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.query.SearchQuery.ActorKind;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
     */
    public record Event(
        UUID eventId,
        Key eventType,
        Instant occurredAt,
        Optional<Key> server,
        Optional<Key> world,
        OptionalInt x,
        OptionalInt y,
        OptionalInt z,
        Optional<ActorKind> actorKind,
        Optional<UUID> actorUuid,
        Optional<String> actorName,
        Optional<Key> actorType,
        Optional<Key> targetType,
        Optional<String> searchText
    ) {

        public Event {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
            Objects.requireNonNull(z, "z");
            Objects.requireNonNull(actorKind, "actorKind");
            Objects.requireNonNull(actorUuid, "actorUuid");
            Objects.requireNonNull(actorName, "actorName");
            Objects.requireNonNull(actorType, "actorType");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(searchText, "searchText");
        }

        public SearchRequest.Cursor cursor(SearchRequest.Direction direction) {
            return new SearchRequest.Cursor(this.occurredAt, this.eventId, direction);
        }
    }
}
