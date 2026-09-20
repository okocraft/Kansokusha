package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@NotNullByDefault
public final class DuckDbEventWriter {

    private static final long MIN_FINITE_TIMESTAMP_MILLIS = -Long.MAX_VALUE + 1;
    private static final long MAX_FINITE_TIMESTAMP_MILLIS = Long.MAX_VALUE - 1;

    private static final String INSERT_EVENT = """
        INSERT INTO events (
            payload_generation_id, occurred_at, server_id, world_id,
            block_x, block_y, block_z, subject_player_uuid,
            retention_policy_id, expires_at, payload
        ) VALUES (
            ?, make_timestamp_ms(?), ?, ?, ?, ?, ?, CAST(? AS UUID),
            ?, make_timestamp_ms(?), ?
        )
        """;

    private final DuckDbDatabase database;
    private final AfterBatchInsert afterBatchInsert;

    public DuckDbEventWriter(DuckDbDatabase database) {
        this(database, (connection, eventCount) -> {
        });
    }

    DuckDbEventWriter(DuckDbDatabase database, AfterBatchInsert afterBatchInsert) {
        this.database = Objects.requireNonNull(database, "database");
        this.afterBatchInsert = Objects.requireNonNull(afterBatchInsert, "afterBatchInsert");
    }

    public int append(List<AcceptedEvent> events) throws SQLException {
        var batch = List.copyOf(Objects.requireNonNull(events, "events"));
        if (batch.isEmpty()) {
            return 0;
        }
        return this.database.transaction(connection -> this.append(connection, batch));
    }

    private int append(Connection connection, List<AcceptedEvent> events) throws SQLException {
        var payloadIds = new HashMap<EventTypeDefinition, Integer>();
        var serverIds = new HashMap<Key, Integer>();
        var worldIds = new HashMap<WorldIdentity, Integer>();
        var retentionIds = new HashMap<Key, Integer>();

        try (var statement = connection.prepareStatement(INSERT_EVENT)) {
            for (var event : events) {
                var submission = event.submission();
                var definition = new EventTypeDefinition(
                    submission.eventType(),
                    submission.payloadGeneration()
                );
                var payloadId = cached(
                    payloadIds,
                    definition,
                    () -> DuckDbEventTypeRegistry.resolve(connection, definition).id()
                );
                var serverId = cached(
                    serverIds,
                    submission.serverKey(),
                    () -> resolveKey(connection, "servers", "server_key", submission.serverKey())
                );
                var worldId = submission.worldKey() == null
                    ? null
                    : cached(
                        worldIds,
                        new WorldIdentity(serverId, submission.worldKey()),
                        () -> resolveWorld(connection, serverId, submission.worldKey())
                    );
                var retentionId = cached(
                    retentionIds,
                    event.retentionPolicyKey(),
                    () -> resolveKey(
                        connection,
                        "retention_policies",
                        "retention_policy_key",
                        event.retentionPolicyKey()
                    )
                );

                bind(statement, event, payloadId, serverId, worldId, retentionId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("DuckDB did not insert exactly one event row");
                }
            }
        }

        this.afterBatchInsert.accept(connection, events.size());
        return events.size();
    }

    private static int resolveKey(
        Connection connection,
        String table,
        String keyColumn,
        Key key
    ) throws SQLException {
        try (var select = connection.prepareStatement(
            "SELECT id FROM " + table + " WHERE " + keyColumn + " = ?"
        )) {
            select.setString(1, key.asString());
            try (var result = select.executeQuery()) {
                if (result.next()) {
                    return result.getInt("id");
                }
            }
        }

        try (var insert = connection.prepareStatement(
            "INSERT INTO " + table + " (" + keyColumn + ") VALUES (?) RETURNING id"
        )) {
            insert.setString(1, key.asString());
            try (var result = insert.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Creating metadata returned no identifier for " + key.asString());
                }
                return result.getInt("id");
            }
        }
    }

    private static int resolveWorld(Connection connection, int serverId, Key worldKey)
        throws SQLException {
        try (var select = connection.prepareStatement(
            "SELECT id FROM worlds WHERE server_id = ? AND world_key = ?"
        )) {
            select.setInt(1, serverId);
            select.setString(2, worldKey.asString());
            try (var result = select.executeQuery()) {
                if (result.next()) {
                    return result.getInt("id");
                }
            }
        }

        try (var insert = connection.prepareStatement(
            "INSERT INTO worlds (server_id, world_key) VALUES (?, ?) RETURNING id"
        )) {
            insert.setInt(1, serverId);
            insert.setString(2, worldKey.asString());
            try (var result = insert.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Creating world returned no identifier for " + worldKey.asString());
                }
                return result.getInt("id");
            }
        }
    }

    private static void bind(
        PreparedStatement statement,
        AcceptedEvent event,
        int payloadId,
        int serverId,
        @Nullable Integer worldId,
        int retentionId
    ) throws SQLException {
        var submission = event.submission();
        var position = submission.position();
        var subject = submission.subject();

        statement.setInt(1, payloadId);
        statement.setLong(2, finiteMillis(submission.occurredAt(), true, "occurredAt"));
        statement.setInt(3, serverId);
        setNullableInt(statement, 4, worldId);
        setNullableInt(statement, 5, position == null ? null : position.x());
        setNullableInt(statement, 6, position == null ? null : position.y());
        setNullableInt(statement, 7, position == null ? null : position.z());

        if (subject == null) {
            statement.setNull(8, Types.VARCHAR);
        } else if (subject instanceof PlayerSubject player) {
            statement.setString(8, player.uniqueId().toString());
        } else {
            throw new SQLException("Unsupported event subject type: " + subject.getClass().getName());
        }

        statement.setInt(9, retentionId);
        statement.setLong(10, finiteMillis(event.expiresAt(), false, "expiresAt"));
        statement.setBytes(11, submission.payload().copyBytes());
    }

    private static void setNullableInt(PreparedStatement statement, int index, @Nullable Integer value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static long finiteMillis(Instant instant, boolean truncate, String field)
        throws SQLException {
        final long millis;
        try {
            millis = (truncate ? instant.truncatedTo(ChronoUnit.MILLIS) : instant).toEpochMilli();
        } catch (ArithmeticException e) {
            throw new SQLException(field + " is outside the DuckDB TIMESTAMP_MS range", e);
        }
        if (millis < MIN_FINITE_TIMESTAMP_MILLIS || millis > MAX_FINITE_TIMESTAMP_MILLIS) {
            throw new SQLException(field + " resolves to a DuckDB TIMESTAMP_MS infinity sentinel");
        }
        return millis;
    }

    private static <K> int cached(Map<K, Integer> cache, K key, SqlIntSupplier supplier)
        throws SQLException {
        var existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        var created = supplier.getAsInt();
        cache.put(key, created);
        return created;
    }

    private record WorldIdentity(int serverId, Key worldKey) {
    }

    @FunctionalInterface
    private interface SqlIntSupplier {
        int getAsInt() throws SQLException;
    }

    @FunctionalInterface
    interface AfterBatchInsert {
        void accept(Connection connection, int eventCount) throws SQLException;
    }
}
