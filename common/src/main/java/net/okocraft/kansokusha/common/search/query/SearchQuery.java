package net.okocraft.kansokusha.common.search.query;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Platform-neutral, immutable search query.
 *
 * <p>Values within one condition field are alternatives (OR). Different non-empty fields are
 * combined (AND). Exclusions use the same grouping rules, then reject matching events.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public record SearchQuery(
    Conditions conditions,
    Conditions exclusions,
    Order order,
    OptionalInt limit
) {

    public SearchQuery {
        Objects.requireNonNull(conditions, "conditions");
        Objects.requireNonNull(exclusions, "exclusions");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(limit, "limit");
        if (limit.isPresent() && limit.getAsInt() <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
    }

    public enum Order {
        NEWEST,
        OLDEST
    }

    public enum ActorKind {
        PLAYER,
        ENTITY,
        BLOCK
    }

    /**
     * Grouped search conditions. Each set is an OR group; non-empty sets are ANDed with each other.
     */
    public record Conditions(
        Set<String> users,
        Set<Key> actions,
        Set<TimeRange> timeRanges,
        Set<Key> targets,
        Set<String> filters,
        Set<UUID> actorUuids,
        Set<ActorKind> actorKinds,
        Set<Key> actorTypes,
        Set<Key> worlds,
        Set<Position> positions,
        Set<Around> around
    ) {

        public Conditions {
            users = Set.copyOf(users);
            actions = Set.copyOf(actions);
            timeRanges = Set.copyOf(timeRanges);
            targets = Set.copyOf(targets);
            filters = Set.copyOf(filters);
            actorUuids = Set.copyOf(actorUuids);
            actorKinds = Set.copyOf(actorKinds);
            actorTypes = Set.copyOf(actorTypes);
            worlds = Set.copyOf(worlds);
            positions = Set.copyOf(positions);
            around = Set.copyOf(around);
        }

        public static Conditions empty() {
            return new Conditions(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of()
            );
        }

        public static Conditions position(Position position) {
            return new Conditions(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(position), Set.of()
            );
        }

        public boolean isEmpty() {
            return this.equals(empty());
        }
    }

    /**
     * Half-open time range: {@code fromInclusive <= eventTime < toExclusive}.
     */
    public record TimeRange(
        Optional<Instant> fromInclusive,
        Optional<Instant> toExclusive
    ) {

        public TimeRange {
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
            if (fromInclusive.isEmpty() && toExclusive.isEmpty()) {
                throw new IllegalArgumentException("time range must have at least one bound");
            }
            if (
                fromInclusive.isPresent()
                    && toExclusive.isPresent()
                    && !fromInclusive.get().isBefore(toExclusive.get())
            ) {
                throw new IllegalArgumentException("time range start must be before its end");
            }
        }

        public static TimeRange bounded(Instant fromInclusive, Instant toExclusive) {
            return new TimeRange(Optional.of(fromInclusive), Optional.of(toExclusive));
        }
    }

    public record Position(Key world, int x, int y, int z) {

        public Position {
            Objects.requireNonNull(world, "world");
        }
    }

    /**
     * Inclusive X/Z square around a center. Y is not bounded.
     */
    public record Around(Key world, int x, int z, int radius) {

        public Around {
            Objects.requireNonNull(world, "world");
            if (radius <= 0) {
                throw new IllegalArgumentException("around radius must be greater than zero");
            }
        }
    }
}
