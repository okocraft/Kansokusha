package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
            payload_generation_id,
            occurred_at,
            server_id,
            world_id,
            block_x,
            block_y,
            block_z,
            subject_player_uuid,
            retention_policy_id,
            expires_at,
            payload
        ) VALUES (
            ?, make_timestamp_ms(?), ?, ?, ?, ?, ?, CAST(? AS UUID), ?, make_timestamp_ms(?), ?
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
        var payloadGenerationIds = new HashMap<EventTypeDefinition, Integer>();
        var serverIds = new HashMap<Key, Integer>();
        var worldIds = new HashMap<WorldIdentity, Integer>();
        var retentionPolicyIds = new HashMap<Key, Integer>();

        try (var statement = connection.prepareStatement(INSERT_EVENT)) {
            for (var acceptedEvent : events) {
                var submission = acceptedEvent.submission();

                var payloadGenerationId = resolvePayloadGeneration(
                    connection,
                    payloadGenerationIds,
                    submission
                );
                var serverId = resolveServer(connection, serverIds, submission.serverKey());
                var worldId = resolveWorld(
                    connection,
                    worldIds,
                    serverId,
                    submission.worldKey()
                );
                var retentionPolicyId = resolveRetentionPolicy(
                    connection,
                    retentionPolicyIds,
                    acceptedEvent.retentionPolicyKey()
                );

                bindEvent(
                    statement,
                    acceptedEvent,
                    payloadGenerationId,
                    serverId,
                    worldId,
                    retentionPolicyId
                );
                statement.addBatch();
            }

            var updateCounts = statement.executeBatch();
            validateBatchResult(updateCounts, events.size());
        }

        this.afterBatchInsert.accept(connection, events.size());
        return events.size();
    }

    private static int resolvePayloadGeneration(
        Connection connection,
        Map<EventTypeDefinition, Integer> cache,
        EventSubmission submission
    ) throws SQLException {
        var definition = new EventTypeDefinition(
            submission.eventType(),
            submission.payloadGeneration()
        );
        var cached = cache.get(definition);
        if (cached != null) {
            return cached;
        }

        var id = DuckDbEventTypeRegistry.resolve(connection, definition).id();
        cache.put(definition, id);
        return id;
    }

    private static int resolveServer(
        Connection connection,
        Map<Key, Integer> cache,
        Key serverKey
    ) throws SQLException {
        var cached = cache.get(serverKey);
        if (cached != null) {
            return cached;
        }

        var id = findOrCreateKey(
            connection,
            "SELECT id FROM servers WHERE server_key = ?",
            "INSERT INTO servers (server_key) VALUES (?) RETURNING id",
            serverKey
        );
        cache.put(serverKey, id);
        return id;
    }

    private static Integer resolveWorld(
        Connection connection,
        Map<WorldIdentity, Integer> cache,
        int serverId,
        @Nullable Key worldKey
    ) throws SQLException {
        if (worldKey == null) {
            return null;
        }

        var identity = new WorldIdentity(serverId, worldKey);
        var cached = cache.get(identity);
        if (cached != null) {
            return cached;
        }

        final int id;
        try (var select = connection.prepareStatement(
            "SELECT id FROM worlds WHERE server_id = ? AND world_key = ?"
        )) {
            select.setInt(1, serverId);
            select.setString(2, worldKey.asString());

            try (var result = select.executeQuery()) {
                if (result.next()) {
                    id = result.getInt("id");
                } else {
                    id = createWorld(connection, serverId, worldKey);
                }
            }
        }

        cache.put(identity, id);
        return id;
    }

    private static int resolveRetentionPolicy(
        Connection connection,
        Map<Key, Integer> cache,
        Key retentionPolicyKey
    ) throws SQLException {
        var cached = cache.get(retentionPolicyKey);
        if (cached != null) {
            return cached;
        }

        var id = findOrCreateKey(
            connection,
            "SELECT id FROM retention_policies WHERE retention_policy_key = ?",
            "INSERT INTO retention_policies (retention_policy_key) VALUES (?) RETURNING id",
            retentionPolicyKey
        );
        cache.put(retentionPolicyKey, id);
        return id;
    }

    private static int findOrCreateKey(
        Connection connection,
        String selectSql,
        String insertSql,
        Key key
    ) throws SQLException {
        try (var select = connection.prepareStatement(selectSql)) {
            select.setString(1, key.asString());

            try (var result = select.executeQuery()) {
                if (result.next()) {
                    return result.getInt("id");
                }
            }
        }

        try (var insert = connection.prepareStatement(insertSql)) {
            insert.setString(1, key.asString());

            try (var result = insert.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Creating metadata returned no identifier for " + key.asString());
                }
                return result.getInt("id");
            }
        }
    }

    private static int createWorld(
        Connection connection,
        int serverId,
        Key worldKey
    ) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
                INSERT INTO worlds (server_id, world_key)
                VALUES (?, ?)
                RETURNING id
                """
        )) {
            statement.setInt(1, serverId);
            statement.setString(2, worldKey.asString());

            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException(
                        "Creating world returned no identifier for " + worldKey.asString()
                    );
                }
                return result.getInt("id");
            }
        }
    }

    private static void bindEvent(
        PreparedStatement statement,
        AcceptedEvent acceptedEvent,
        int payloadGenerationId,
        int serverId,
        @Nullable Integer worldId,
        int retentionPolicyId
    ) throws SQLException {
        var submission = acceptedEvent.submission();
        var position = submission.position();
        var subject = submission.subject();

        statement.setInt(1, payloadGenerationId);
        statement.setLong(2, finiteTimestampMillis(submission.occurredAt(), true, "occurredAt"));
        statement.setInt(3, serverId);

        if (worldId == null) {
            statement.setNull(4, Types.INTEGER);
        } else {
            statement.setInt(4, worldId);
        }

        if (position == null) {
            statement.setNull(5, Types.INTEGER);
            statement.setNull(6, Types.INTEGER);
            statement.setNull(7, Types.INTEGER);
        } else {
            statement.setInt(5, position.x());
            statement.setInt(6, position.y());
            statement.setInt(7, position.z());
        }

        if (subject == null) {
            statement.setNull(8, Types.VARCHAR);
        } else if (subject instanceof PlayerSubject player) {
            statement.setString(8, player.uniqueId().toString());
        } else {
            throw new SQLException("Unsupported event subject type: " + subject.getClass().getName());
        }

        statement.setInt(9, retentionPolicyId);
        statement.setLong(10, finiteTimestampMillis(acceptedEvent.expiresAt(), false, "expiresAt"));
        statement.setBytes(11, submission.payload().copyBytes());
    }

    private static long finiteTimestampMillis(
        Instant instant,
        boolean truncateToMilliseconds,
        String field
    ) throws SQLException {
        var normalized = truncateToMilliseconds
            ? instant.truncatedTo(ChronoUnit.MILLIS)
            : instant;

        final long epochMillis;
        try {
            epochMillis = normalized.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new SQLException(field + " is outside the DuckDB TIMESTAMP_MS range", e);
        }

        if (epochMillis < MIN_FINITE_TIMESTAMP_MILLIS || epochMillis > MAX_FINITE_TIMESTAMP_MILLIS) {
            throw new SQLException(field + " resolves to a DuckDB TIMESTAMP_MS infinity sentinel");
        }

        return epochMillis;
    }

    private static void validateBatchResult(int[] updateCounts, int expectedCount) throws SQLException {
        if (updateCounts.length != expectedCount) {
            throw new SQLException(
                "DuckDB batch result count mismatch: expected "
                    + expectedCount + " but got " + updateCounts.length
            );
        }

        for (var updateCount : updateCounts) {
            if (updateCount == Statement.EXECUTE_FAILED) {
                throw new SQLException("DuckDB reported a failed event batch entry.");
            }
        }
    }

    private record WorldIdentity(int serverId, Key worldKey) {
    }

    @FunctionalInterface
    interface AfterBatchInsert {

        void accept(Connection connection, int eventCount) throws SQLException;
    }
}
