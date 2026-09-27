package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.search.query.SearchQuery.ActorKind;
import net.okocraft.kansokusha.common.search.query.SearchQuery.Conditions;
import org.duckdb.DuckDBConnection;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Compiles typed search queries to parameterized DuckDB SQL.
 */
@NotNullByDefault
final class DuckDbEventSearch {

    private DuckDbEventSearch() {
    }

    static SearchPage search(DuckDBConnection connection, SearchRequest request) throws SQLException {
        if (request.constraints().allowedEventTypes().isEmpty()) {
            return emptyPage();
        }

        var includedUsers = resolveUsers(connection, request.query().conditions().users());
        var excludedUsers = resolveUsers(connection, request.query().exclusions().users());

        var where = new ArrayList<String>();
        var parameters = new ArrayList<Object>();

        addAllowedEventTypes(where, parameters, request.constraints().allowedEventTypes());

        var included = compileConditions(
            request.query().conditions(),
            includedUsers,
            request.radiusCenter()
        );
        if (!included.sql().isEmpty()) {
            where.add(included.sql());
            parameters.addAll(included.parameters());
        }

        if (hasConditions(request.query().exclusions())) {
            var excluded = compileConditions(
                request.query().exclusions(),
                excludedUsers,
                request.radiusCenter()
            );
            where.add("NOT (" + excluded.sql() + ")");
            parameters.addAll(excluded.parameters());
        }

        var cursor = request.cursor();
        var logicalAscending = request.query().order() == SearchQuery.Order.OLDEST;
        if (cursor.isPresent()) {
            addCursor(where, parameters, cursor.get(), logicalAscending);
        }

        var fetchingPrevious = cursor.isPresent()
            && cursor.get().direction() == SearchRequest.Direction.PREVIOUS;
        var fetchAscending = fetchingPrevious ? !logicalAscending : logicalAscending;
        var direction = fetchAscending ? "ASC" : "DESC";

        var sql = """
            SELECT
                e.event_id,
                e.event_type,
                epoch_ms(e.occurred_at) AS occurred_at_ms,
                e.server,
                e.world,
                e.x,
                e.y,
                e.z,
                e.actor_kind,
                e.actor_uuid,
                e.actor_type,
                e.target_type,
                st.search_text
            FROM events e
            LEFT JOIN event_search_text st ON st.event_id = e.event_id
            WHERE %s
            ORDER BY e.occurred_at %s, e.event_id %s
            LIMIT ?
            """.formatted(String.join(" AND ", where), direction, direction);

        var events = new ArrayList<SearchPage.Event>();
        try (var statement = connection.prepareStatement(sql)) {
            var index = 1;
            for (var parameter : parameters) {
                statement.setObject(index++, parameter);
            }
            statement.setLong(index, (long) request.limit() + 1L);

            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(readEvent(rows));
                }
            }
        }

        var hasMoreInRequestedDirection = events.size() > request.limit();
        if (hasMoreInRequestedDirection) {
            events.remove(events.size() - 1);
        }
        if (fetchingPrevious) {
            Collections.reverse(events);
        }

        var hasPrevious = false;
        var hasNext = false;
        if (cursor.isEmpty()) {
            hasNext = hasMoreInRequestedDirection;
        } else if (cursor.get().direction() == SearchRequest.Direction.NEXT) {
            hasPrevious = !events.isEmpty();
            hasNext = hasMoreInRequestedDirection;
        } else {
            hasPrevious = hasMoreInRequestedDirection;
            hasNext = !events.isEmpty();
        }

        var previousCursor = hasPrevious && !events.isEmpty()
            ? Optional.of(events.getFirst().cursor(SearchRequest.Direction.PREVIOUS))
            : Optional.<SearchRequest.Cursor>empty();
        var nextCursor = hasNext && !events.isEmpty()
            ? Optional.of(events.getLast().cursor(SearchRequest.Direction.NEXT))
            : Optional.<SearchRequest.Cursor>empty();

        return new SearchPage(events, nextCursor, previousCursor);
    }

    private static SearchPage emptyPage() {
        return new SearchPage(List.of(), Optional.empty(), Optional.empty());
    }

    private static void addAllowedEventTypes(
        List<String> where,
        List<Object> parameters,
        Set<Key> allowedEventTypes
    ) {
        where.add(inPredicate("e.event_type", allowedEventTypes.size()));
        for (var eventType : allowedEventTypes) {
            parameters.add(eventType.asString());
        }
    }

    private static Set<UUID> resolveUsers(
        DuckDBConnection connection,
        Set<String> names
    ) throws SQLException {
        if (names.isEmpty()) {
            return Set.of();
        }

        var resolved = new LinkedHashSet<UUID>();
        try (var statement = connection.prepareStatement("""
            SELECT player_uuid
            FROM player_name_history
            WHERE normalized_name = ?
            ORDER BY last_seen DESC, last_event_id DESC
            LIMIT 1
            """)) {
            for (var name : names) {
                statement.setString(1, name.toLowerCase(Locale.ROOT));
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        resolved.add(rows.getObject(1, UUID.class));
                    }
                }
            }
        }
        return Set.copyOf(resolved);
    }

    private static Predicate compileConditions(
        Conditions conditions,
        Set<UUID> resolvedUsers,
        Optional<SearchRequest.RadiusCenter> radiusCenter
    ) {
        var groups = new ArrayList<String>();
        var parameters = new ArrayList<Object>();

        if (!conditions.users().isEmpty()) {
            if (resolvedUsers.isEmpty()) {
                groups.add("FALSE");
            } else {
                groups.add(
                    "(e.actor_kind = 'player' AND "
                        + inPredicate("e.actor_uuid", resolvedUsers.size()) + ")"
                );
                parameters.addAll(resolvedUsers);
            }
        }

        addKeyGroup(groups, parameters, "e.event_type", conditions.actions());
        addTimeRanges(groups, parameters, conditions.timeRanges());
        addRadii(groups, parameters, conditions.radii(), radiusCenter);
        addKeyGroup(groups, parameters, "e.target_type", conditions.targets());
        addFilters(groups, parameters, conditions.filters());
        addUuidGroup(groups, parameters, "e.actor_uuid", conditions.actorUuids());
        addActorKinds(groups, parameters, conditions.actorKinds());
        addKeyGroup(groups, parameters, "e.actor_type", conditions.actorTypes());
        addKeyGroup(groups, parameters, "e.world", conditions.worlds());
        addPositions(groups, parameters, conditions.positions());
        addAround(groups, parameters, conditions.around());

        return new Predicate(String.join(" AND ", groups), List.copyOf(parameters));
    }

    private static void addKeyGroup(
        List<String> groups,
        List<Object> parameters,
        String column,
        Set<Key> values
    ) {
        if (values.isEmpty()) {
            return;
        }
        groups.add(inPredicate(column, values.size()));
        for (var value : values) {
            parameters.add(value.asString());
        }
    }

    private static void addUuidGroup(
        List<String> groups,
        List<Object> parameters,
        String column,
        Set<UUID> values
    ) {
        if (values.isEmpty()) {
            return;
        }
        groups.add(inPredicate(column, values.size()));
        parameters.addAll(values);
    }

    private static void addActorKinds(
        List<String> groups,
        List<Object> parameters,
        Set<ActorKind> values
    ) {
        if (values.isEmpty()) {
            return;
        }
        groups.add(inPredicate("e.actor_kind", values.size()));
        for (var value : values) {
            parameters.add(value.name().toLowerCase(Locale.ROOT));
        }
    }

    private static void addTimeRanges(
        List<String> groups,
        List<Object> parameters,
        Set<SearchQuery.TimeRange> ranges
    ) {
        if (ranges.isEmpty()) {
            return;
        }

        var alternatives = new ArrayList<String>();
        for (var range : ranges) {
            var parts = new ArrayList<String>();
            range.fromInclusive().ifPresent(from -> {
                parts.add("e.occurred_at >= epoch_ms(?)");
                parameters.add(from.toEpochMilli());
            });
            range.toExclusive().ifPresent(to -> {
                parts.add("e.occurred_at < epoch_ms(?)");
                parameters.add(to.toEpochMilli());
            });
            alternatives.add("(" + String.join(" AND ", parts) + ")");
        }
        groups.add("(" + String.join(" OR ", alternatives) + ")");
    }

    private static void addRadii(
        List<String> groups,
        List<Object> parameters,
        Set<Integer> radii,
        Optional<SearchRequest.RadiusCenter> radiusCenter
    ) {
        if (radii.isEmpty()) {
            return;
        }

        var center = radiusCenter.orElseThrow();
        var alternatives = new ArrayList<String>();
        for (var radius : radii) {
            alternatives.add(
                "(e.world = ? AND e.x >= ? AND e.x <= ? AND e.z >= ? AND e.z <= ?)"
            );
            parameters.add(center.world().asString());
            parameters.add((long) center.x() - radius);
            parameters.add((long) center.x() + radius);
            parameters.add((long) center.z() - radius);
            parameters.add((long) center.z() + radius);
        }
        groups.add("(" + String.join(" OR ", alternatives) + ")");
    }

    private static void addFilters(
        List<String> groups,
        List<Object> parameters,
        Set<String> filters
    ) {
        if (filters.isEmpty()) {
            return;
        }

        var alternatives = new ArrayList<String>();
        for (var filter : filters) {
            alternatives.add("contains(lower(st.search_text), lower(?))");
            parameters.add(filter);
        }
        groups.add(
            "(st.search_text IS NOT NULL AND (" + String.join(" OR ", alternatives) + "))"
        );
    }

    private static void addPositions(
        List<String> groups,
        List<Object> parameters,
        Set<SearchQuery.Position> positions
    ) {
        if (positions.isEmpty()) {
            return;
        }

        var alternatives = new ArrayList<String>();
        for (var position : positions) {
            alternatives.add("(e.world = ? AND e.x = ? AND e.y = ? AND e.z = ?)");
            parameters.add(position.world().asString());
            parameters.add(position.x());
            parameters.add(position.y());
            parameters.add(position.z());
        }
        groups.add("(" + String.join(" OR ", alternatives) + ")");
    }

    private static void addAround(
        List<String> groups,
        List<Object> parameters,
        Set<SearchQuery.Around> around
    ) {
        if (around.isEmpty()) {
            return;
        }

        var alternatives = new ArrayList<String>();
        for (var area : around) {
            alternatives.add(
                "(e.world = ? AND e.x >= ? AND e.x <= ? AND e.z >= ? AND e.z <= ?)"
            );
            parameters.add(area.world().asString());
            parameters.add((long) area.x() - area.radius());
            parameters.add((long) area.x() + area.radius());
            parameters.add((long) area.z() - area.radius());
            parameters.add((long) area.z() + area.radius());
        }
        groups.add("(" + String.join(" OR ", alternatives) + ")");
    }

    private static String inPredicate(String column, int size) {
        return column + " IN (" + String.join(", ", Collections.nCopies(size, "?")) + ")";
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

    private static void addCursor(
        List<String> where,
        List<Object> parameters,
        SearchRequest.Cursor cursor,
        boolean logicalAscending
    ) {
        var next = cursor.direction() == SearchRequest.Direction.NEXT;
        var greaterThan = logicalAscending == next;
        var operator = greaterThan ? ">" : "<";
        where.add(
            "(e.occurred_at " + operator + " epoch_ms(?)"
                + " OR (e.occurred_at = epoch_ms(?) AND e.event_id " + operator + " ?))"
        );
        var millis = cursor.occurredAt().toEpochMilli();
        parameters.add(millis);
        parameters.add(millis);
        parameters.add(cursor.eventId());
    }

    private static SearchPage.Event readEvent(ResultSet rows) throws SQLException {
        return new SearchPage.Event(
            rows.getObject("event_id", UUID.class),
            Key.key(rows.getString("event_type")),
            Instant.ofEpochMilli(rows.getLong("occurred_at_ms")),
            optionalKey(rows.getString("server")),
            optionalKey(rows.getString("world")),
            optionalInt(rows, "x"),
            optionalInt(rows, "y"),
            optionalInt(rows, "z"),
            optionalActorKind(rows.getString("actor_kind")),
            Optional.ofNullable(rows.getObject("actor_uuid", UUID.class)),
            optionalKey(rows.getString("actor_type")),
            optionalKey(rows.getString("target_type")),
            Optional.ofNullable(rows.getString("search_text"))
        );
    }

    private static Optional<Key> optionalKey(String value) {
        return value == null ? Optional.empty() : Optional.of(Key.key(value));
    }

    private static OptionalInt optionalInt(ResultSet rows, String column) throws SQLException {
        var value = rows.getInt(column);
        return rows.wasNull() ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static Optional<ActorKind> optionalActorKind(String value) {
        return value == null
            ? Optional.empty()
            : Optional.of(ActorKind.valueOf(value.toUpperCase(Locale.ROOT)));
    }

    private record Predicate(String sql, List<Object> parameters) {
    }
}
