package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.query.SearchQuery.ActorKind;
import org.duckdb.DuckDBConnection;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@NotNullByDefault
final class DuckDbEventLookup {

    private DuckDbEventLookup() {
    }

    static Optional<EventDetail> find(DuckDBConnection connection, UUID eventId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT
                e.event_id,
                e.event_type,
                e.payload_generation,
                epoch_ms(e.occurred_at) AS occurred_at_ms,
                e.server,
                e.world,
                e.x,
                e.y,
                e.z,
                e.actor_kind,
                e.actor_uuid,
                (
                    SELECT pnh.name
                    FROM player_name_history pnh
                    WHERE pnh.player_uuid = e.actor_uuid
                    ORDER BY pnh.last_seen DESC, pnh.last_event_id DESC
                    LIMIT 1
                ) AS actor_name,
                e.actor_type,
                e.target_type,
                epoch_ms(e.expires_at) AS expires_at_ms,
                st.search_text
            FROM events e
            LEFT JOIN event_search_text st ON st.event_id = e.event_id
            WHERE e.event_id = ?
            LIMIT 1
            """)) {
            statement.setObject(1, eventId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readEvent(rows)) : Optional.empty();
            }
        }
    }

    private static EventDetail readEvent(ResultSet rows) throws SQLException {
        return new EventDetail(
            rows.getObject("event_id", UUID.class),
            Key.key(rows.getString("event_type")),
            new PayloadGeneration(rows.getInt("payload_generation")),
            Instant.ofEpochMilli(rows.getLong("occurred_at_ms")),
            optionalKey(rows.getString("server")),
            optionalKey(rows.getString("world")),
            optionalInt(rows, "x"),
            optionalInt(rows, "y"),
            optionalInt(rows, "z"),
            optionalActorKind(rows.getString("actor_kind")),
            Optional.ofNullable(rows.getObject("actor_uuid", UUID.class)),
            Optional.ofNullable(rows.getString("actor_name")),
            optionalKey(rows.getString("actor_type")),
            optionalKey(rows.getString("target_type")),
            Instant.ofEpochMilli(rows.getLong("expires_at_ms")),
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
}
