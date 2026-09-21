package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import org.duckdb.DuckDBConnection;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@NotNullByDefault
public final class DuckDbEventWriter implements EventBatchWriter {

    private static final long MIN_FINITE_TIMESTAMP_MILLIS = -Long.MAX_VALUE + 1;
    private static final long MAX_FINITE_TIMESTAMP_MILLIS = Long.MAX_VALUE - 1;

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

    @Override
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
        var resolved = new ArrayList<ResolvedEvent>(events.size());

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
            resolved.add(new ResolvedEvent(event, payloadId, serverId, worldId, retentionId));
        }

        var duckConnection = connection.unwrap(DuckDBConnection.class);
        try (var appender = duckConnection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "events")) {
            for (var event : resolved) {
                appendRow(appender, event);
            }
            appender.flush();
        }

        this.afterBatchInsert.accept(connection, events.size());
        return events.size();
    }

    private static void appendRow(
        org.duckdb.DuckDBAppender appender,
        ResolvedEvent resolved
    ) throws SQLException {
        var event = resolved.event();
        var submission = event.submission();
        var position = submission.position();
        var subject = submission.subject();

        appender.beginRow()
            .append(resolved.payloadGenerationId())
            .appendEpochMillis(finiteMillis(submission.occurredAt(), true, "occurredAt"))
            .append(resolved.serverId())
            .append(resolved.worldId());

        if (position == null) {
            appender.appendNull().appendNull().appendNull();
        } else {
            appender.append(position.x()).append(position.y()).append(position.z());
        }

        if (subject == null) {
            appender.appendNull();
        } else if (subject instanceof PlayerSubject player) {
            appender.append(player.uniqueId());
        } else {
            throw new SQLException("Unsupported event subject type: " + subject.getClass().getName());
        }

        appender
            .append(resolved.retentionPolicyId())
            .appendEpochMillis(finiteMillis(event.expiresAt(), false, "expiresAt"))
            .append(submission.payload().copyBytes())
            .endRow();
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

    private record ResolvedEvent(
        AcceptedEvent event,
        int payloadGenerationId,
        int serverId,
        @Nullable Integer worldId,
        int retentionPolicyId
    ) {
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
