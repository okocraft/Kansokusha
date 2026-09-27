package net.okocraft.kansokusha.common.search.query;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

        if (isContradiction(conditions, exclusions)) {
            throw new IllegalArgumentException(
                "query inclusion predicate is fully excluded by the exclusion predicate"
            );
        }
    }

    private static boolean isContradiction(Conditions included, Conditions excluded) {
        if (!hasConditions(excluded)) {
            return false;
        }
        return implies(included.users(), excluded.users())
            && implies(included.actions(), excluded.actions())
            && timeRangesImply(included.timeRanges(), excluded.timeRanges())
            && implies(included.radii(), excluded.radii())
            && implies(included.targets(), excluded.targets())
            && implies(included.filters(), excluded.filters())
            && implies(included.actorUuids(), excluded.actorUuids())
            && implies(included.actorKinds(), excluded.actorKinds())
            && implies(included.actorTypes(), excluded.actorTypes())
            && implies(included.worlds(), excluded.worlds())
            && implies(included.positions(), excluded.positions())
            && implies(included.around(), excluded.around());
    }

    private static boolean hasConditions(Conditions conditions) {
        return !conditions.users().isEmpty()
            || !conditions.actions().isEmpty()
            || !conditions.timeRanges().isEmpty()
            || !conditions.radii().isEmpty()
            || !conditions.targets().isEmpty()
            || !conditions.filters().isEmpty()
            || !conditions.actorUuids().isEmpty()
            || !conditions.actorKinds().isEmpty()
            || !conditions.actorTypes().isEmpty()
            || !conditions.worlds().isEmpty()
            || !conditions.positions().isEmpty()
            || !conditions.around().isEmpty();
    }

    private static <T> boolean implies(Set<T> included, Set<T> excluded) {
        return excluded.isEmpty() || (!included.isEmpty() && excluded.containsAll(included));
    }

    private static boolean timeRangesImply(Set<TimeRange> included, Set<TimeRange> excluded) {
        if (excluded.isEmpty()) {
            return true;
        }
        if (included.isEmpty()) {
            return false;
        }

        var excludedUnion = mergeTimeRanges(excluded);
        for (var includedRange : included) {
            if (!isCoveredByUnion(includedRange, excludedUnion)) {
                return false;
            }
        }
        return true;
    }

    private static List<Interval> mergeTimeRanges(Set<TimeRange> ranges) {
        var sorted = ranges.stream()
            .map(range -> new Interval(range.fromInclusive(), range.toExclusive()))
            .sorted(Comparator.comparing(
                Interval::fromInclusive,
                SearchQuery::compareLowerBounds
            ))
            .toList();

        var merged = new ArrayList<Interval>();
        for (var next : sorted) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }

            var lastIndex = merged.size() - 1;
            var current = merged.get(lastIndex);
            if (overlapsOrTouches(current, next)) {
                merged.set(
                    lastIndex,
                    new Interval(
                        current.fromInclusive(),
                        laterUpperBound(current.toExclusive(), next.toExclusive())
                    )
                );
            } else {
                merged.add(next);
            }
        }
        return List.copyOf(merged);
    }

    private static boolean isCoveredByUnion(TimeRange included, List<Interval> excludedUnion) {
        for (var excluded : excludedUnion) {
            if (
                lowerBoundAtOrBefore(excluded.fromInclusive(), included.fromInclusive())
                    && upperBoundAtOrAfter(excluded.toExclusive(), included.toExclusive())
            ) {
                return true;
            }
        }
        return false;
    }

    private static int compareLowerBounds(Optional<Instant> first, Optional<Instant> second) {
        if (first.isEmpty()) {
            return second.isEmpty() ? 0 : -1;
        }
        if (second.isEmpty()) {
            return 1;
        }
        return first.get().compareTo(second.get());
    }

    private static boolean overlapsOrTouches(Interval current, Interval next) {
        if (current.toExclusive().isEmpty() || next.fromInclusive().isEmpty()) {
            return true;
        }
        return !current.toExclusive().get().isBefore(next.fromInclusive().get());
    }

    private static Optional<Instant> laterUpperBound(
        Optional<Instant> first,
        Optional<Instant> second
    ) {
        if (first.isEmpty() || second.isEmpty()) {
            return Optional.empty();
        }
        return first.get().isAfter(second.get()) ? first : second;
    }

    private static boolean lowerBoundAtOrBefore(
        Optional<Instant> outer,
        Optional<Instant> inner
    ) {
        if (outer.isEmpty()) {
            return true;
        }
        return inner.isPresent() && !outer.get().isAfter(inner.get());
    }

    private static boolean upperBoundAtOrAfter(
        Optional<Instant> outer,
        Optional<Instant> inner
    ) {
        if (outer.isEmpty()) {
            return true;
        }
        return inner.isPresent() && !outer.get().isBefore(inner.get());
    }

    private record Interval(
        Optional<Instant> fromInclusive,
        Optional<Instant> toExclusive
    ) {
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
        Set<Integer> radii,
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
            radii = Set.copyOf(radii);
            targets = Set.copyOf(targets);
            filters = Set.copyOf(filters);
            actorUuids = Set.copyOf(actorUuids);
            actorKinds = Set.copyOf(actorKinds);
            actorTypes = Set.copyOf(actorTypes);
            worlds = Set.copyOf(worlds);
            positions = Set.copyOf(positions);
            around = Set.copyOf(around);

            for (var radius : radii) {
                if (radius <= 0) {
                    throw new IllegalArgumentException("radius must be greater than zero");
                }
            }
        }

        public static Conditions empty() {
            return new Conditions(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
            );
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

    public record Around(Key world, int x, int z, int radius) {

        public Around {
            Objects.requireNonNull(world, "world");
            if (radius <= 0) {
                throw new IllegalArgumentException("around radius must be greater than zero");
            }
        }
    }
}
