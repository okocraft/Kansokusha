package net.okocraft.kansokusha.common.search;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable execution request for one page of a typed event search.
 *
 * <p>The caller supplies the event types it is allowed to observe. The backend does not call
 * platform permission APIs. A radius center is supplied separately because {@code radius} is
 * relative to command context, while {@code around} carries its own center in the query.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public record SearchRequest(
    SearchQuery query,
    Constraints constraints,
    Optional<RadiusCenter> radiusCenter,
    Optional<Cursor> cursor,
    int defaultLimit
) {

    public static final int MAX_LIMIT = 1000;

    public SearchRequest {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(radiusCenter, "radiusCenter");
        Objects.requireNonNull(cursor, "cursor");
        validateLimit("defaultLimit", defaultLimit);
        if (query.limit().isPresent()) {
            validateLimit("query limit", query.limit().getAsInt());
        }
        if (hasRadius(query) && radiusCenter.isEmpty()) {
            throw new IllegalArgumentException("radius requires a radius center");
        }
    }

    public int limit() {
        return this.query.limit().orElse(this.defaultLimit);
    }

    private static void validateLimit(String name, int limit) {
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                name + " must be between 1 and " + MAX_LIMIT + ": " + limit
            );
        }
    }

    private static boolean hasRadius(SearchQuery query) {
        return !query.conditions().radii().isEmpty() || !query.exclusions().radii().isEmpty();
    }

    /**
     * Backend-enforced visibility constraints supplied by the platform layer.
     *
     * <p>An empty allowed-event-type set intentionally matches no events.</p>
     */
    public record Constraints(Set<Key> allowedEventTypes) {

        public Constraints {
            allowedEventTypes = Set.copyOf(allowedEventTypes);
        }
    }

    /**
     * Contextual center for the {@code radius} modifier. Y is intentionally absent.
     */
    public record RadiusCenter(Key world, int x, int z) {

        public RadiusCenter {
            Objects.requireNonNull(world, "world");
        }
    }

    /**
     * Stable keyset boundary and navigation direction.
     */
    public record Cursor(Instant occurredAt, UUID eventId, Direction direction) {

        public Cursor {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public enum Direction {
        NEXT,
        PREVIOUS
    }
}
