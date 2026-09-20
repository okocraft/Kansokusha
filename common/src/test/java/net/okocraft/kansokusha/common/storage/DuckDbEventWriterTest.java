package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class DuckDbEventWriterTest {

    private static final Key EVENT_A = Key.key("example", "block_break");
    private static final Key EVENT_B = Key.key("example", "block_place");
    private static final Key SERVER_A = Key.key("example", "survival");
    private static final Key SERVER_B = Key.key("example", "creative");
    private static final Key WORLD = Key.key("minecraft", "overworld");
    private static final Key SHORT_RETENTION = Key.key("example", "short");
    private static final Key AUDIT_RETENTION = Key.key("example", "audit");
    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testBatchRoundTripRestoresCompactMetadataAndCommonFields(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("events.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var writer = new DuckDbEventWriter(database);

            var first = accepted(
                EVENT_A,
                1,
                Instant.parse("2026-01-02T03:04:05.123999999Z"),
                SERVER_A,
                null,
                null,
                null,
                SHORT_RETENTION,
                Instant.parse("2026-01-02T04:04:05.123Z"),
                new byte[]{1, 2}
            );
            var second = accepted(
                EVENT_A,
                2,
                Instant.parse("2026-01-02T03:05:06.456789123Z"),
                SERVER_A,
                WORLD,
                new BlockPosition(10, 64, -3),
                new PlayerSubject(PLAYER),
                AUDIT_RETENTION,
                Instant.parse("2026-02-01T03:05:06.456Z"),
                new byte[]{3, 4, 5}
            );
            var third = accepted(
                EVENT_B,
                1,
                Instant.parse("2026-01-02T03:06:07.789111222Z"),
                SERVER_B,
                WORLD,
                null,
                null,
                SHORT_RETENTION,
                Instant.parse("2026-01-03T03:06:07.789Z"),
                new byte[]{6}
            );

            Assertions.assertEquals(3, writer.append(List.of(first, second, third)));

            var rows = readEvents(database.connection());
            Assertions.assertEquals(3, rows.size());

            assertRow(
                rows.get(0),
                EVENT_A,
                1,
                Instant.parse("2026-01-02T03:04:05.123Z").toEpochMilli(),
                SERVER_A,
                null,
                null,
                null,
                null,
                null,
                SHORT_RETENTION,
                first.expiresAt().toEpochMilli(),
                new byte[]{1, 2}
            );
            assertRow(
                rows.get(1),
                EVENT_A,
                2,
                Instant.parse("2026-01-02T03:05:06.456Z").toEpochMilli(),
                SERVER_A,
                WORLD,
                10,
                64,
                -3,
                PLAYER.toString(),
                AUDIT_RETENTION,
                second.expiresAt().toEpochMilli(),
                new byte[]{3, 4, 5}
            );
            assertRow(
                rows.get(2),
                EVENT_B,
                1,
                Instant.parse("2026-01-02T03:06:07.789Z").toEpochMilli(),
                SERVER_B,
                WORLD,
                null,
                null,
                null,
                null,
                SHORT_RETENTION,
                third.expiresAt().toEpochMilli(),
                new byte[]{6}
            );

            Assertions.assertEquals(2, count(database.connection(), "event_types"));
            Assertions.assertEquals(3, count(database.connection(), "payload_generations"));
            Assertions.assertEquals(2, count(database.connection(), "servers"));
            Assertions.assertEquals(2, count(database.connection(), "worlds"));
            Assertions.assertEquals(2, count(database.connection(), "retention_policies"));

            Assertions.assertNotEquals(rows.get(1).worldId(), rows.get(2).worldId());
            rows.forEach(row -> {
                Assertions.assertTrue(row.payloadGenerationId() > 0);
                Assertions.assertTrue(row.serverId() > 0);
                Assertions.assertTrue(row.retentionPolicyId() > 0);
            });
        }
    }

    @Test
    void testInjectedFailureRollsBackBatchAndNewMetadataButPreservesCommittedRows(
        @TempDir Path dir
    ) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("rollback.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var writer = new DuckDbEventWriter(database);

            var committed = accepted(
                EVENT_A,
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                SERVER_A,
                null,
                null,
                null,
                SHORT_RETENTION,
                Instant.parse("2026-01-01T01:00:00Z"),
                new byte[]{1}
            );
            Assertions.assertEquals(1, writer.append(List.of(committed)));

            var failingWriter = new DuckDbEventWriter(
                database,
                (connection, eventCount) -> {
                    Assertions.assertEquals(2, eventCount);
                    Assertions.assertEquals(3, count(connection, "events"));
                    throw new SQLException("injected write failure");
                }
            );

            var failedOne = accepted(
                Key.key("failed", "event_one"),
                1,
                Instant.parse("2026-01-02T00:00:00Z"),
                Key.key("failed", "server"),
                Key.key("failed", "world"),
                new BlockPosition(1, 2, 3),
                null,
                Key.key("failed", "retention"),
                Instant.parse("2026-01-03T00:00:00Z"),
                new byte[]{2}
            );
            var failedTwo = accepted(
                Key.key("failed", "event_two"),
                1,
                Instant.parse("2026-01-02T00:00:01Z"),
                Key.key("failed", "server"),
                null,
                null,
                null,
                Key.key("failed", "retention"),
                Instant.parse("2026-01-03T00:00:01Z"),
                new byte[]{3}
            );

            var error = Assertions.assertThrows(
                SQLException.class,
                () -> failingWriter.append(List.of(failedOne, failedTwo))
            );
            Assertions.assertEquals("injected write failure", error.getMessage());

            Assertions.assertEquals(1, count(database.connection(), "events"));
            Assertions.assertEquals(1, count(database.connection(), "event_types"));
            Assertions.assertEquals(1, count(database.connection(), "payload_generations"));
            Assertions.assertEquals(1, count(database.connection(), "servers"));
            Assertions.assertEquals(0, count(database.connection(), "worlds"));
            Assertions.assertEquals(1, count(database.connection(), "retention_policies"));

            var rows = readEvents(database.connection());
            Assertions.assertEquals(1, rows.size());
            Assertions.assertEquals(EVENT_A, rows.getFirst().eventType());
            Assertions.assertArrayEquals(new byte[]{1}, rows.getFirst().payload());
        }
    }

    @Test
    void testEmptyBatchIsNoOp(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("empty.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var writer = new DuckDbEventWriter(database);

            Assertions.assertEquals(0, writer.append(List.of()));
            Assertions.assertEquals(0, count(database.connection(), "events"));
        }
    }

    private static AcceptedEvent accepted(
        Key eventType,
        int generation,
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        PlayerSubject subject,
        Key retentionPolicy,
        Instant expiresAt,
        byte[] payload
    ) {
        return new AcceptedEvent(
            new EventSubmission(
                eventType,
                new PayloadGeneration(generation),
                occurredAt,
                serverKey,
                worldKey,
                position,
                subject,
                EventPayload.copyOf(payload)
            ),
            retentionPolicy,
            expiresAt
        );
    }

    private static List<StoredEvent> readEvents(Connection connection) throws SQLException {
        var rows = new ArrayList<StoredEvent>();

        try (var statement = connection.createStatement();
             var result = statement.executeQuery(
                 """
                     SELECT
                         e.payload_generation_id,
                         et.event_type_key,
                         pg.generation,
                         epoch_ms(e.occurred_at) AS occurred_at_ms,
                         e.server_id,
                         s.server_key,
                         e.world_id,
                         w.world_key,
                         e.block_x,
                         e.block_y,
                         e.block_z,
                         CAST(e.subject_player_uuid AS VARCHAR) AS subject_player_uuid,
                         e.retention_policy_id,
                         rp.retention_policy_key,
                         epoch_ms(e.expires_at) AS expires_at_ms,
                         e.payload
                     FROM events AS e
                     JOIN payload_generations AS pg ON pg.id = e.payload_generation_id
                     JOIN event_types AS et ON et.id = pg.event_type_id
                     JOIN servers AS s ON s.id = e.server_id
                     LEFT JOIN worlds AS w ON w.id = e.world_id
                     JOIN retention_policies AS rp ON rp.id = e.retention_policy_id
                     ORDER BY e.occurred_at
                     """
             )) {
            while (result.next()) {
                rows.add(
                    new StoredEvent(
                        result.getInt("payload_generation_id"),
                        Key.key(result.getString("event_type_key")),
                        result.getInt("generation"),
                        result.getLong("occurred_at_ms"),
                        result.getInt("server_id"),
                        Key.key(result.getString("server_key")),
                        nullableInt(result, "world_id"),
                        result.getString("world_key") == null
                            ? null
                            : Key.key(result.getString("world_key")),
                        nullableInt(result, "block_x"),
                        nullableInt(result, "block_y"),
                        nullableInt(result, "block_z"),
                        result.getString("subject_player_uuid"),
                        result.getInt("retention_policy_id"),
                        Key.key(result.getString("retention_policy_key")),
                        result.getLong("expires_at_ms"),
                        result.getBytes("payload")
                    )
                );
            }
        }

        return rows;
    }

    private static Integer nullableInt(java.sql.ResultSet result, String column) throws SQLException {
        var value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void assertRow(
        StoredEvent row,
        Key eventType,
        int generation,
        long occurredAtMillis,
        Key serverKey,
        Key worldKey,
        Integer blockX,
        Integer blockY,
        Integer blockZ,
        String playerUuid,
        Key retentionPolicy,
        long expiresAtMillis,
        byte[] payload
    ) {
        Assertions.assertEquals(eventType, row.eventType());
        Assertions.assertEquals(generation, row.generation());
        Assertions.assertEquals(occurredAtMillis, row.occurredAtMillis());
        Assertions.assertEquals(serverKey, row.serverKey());
        Assertions.assertEquals(worldKey, row.worldKey());
        Assertions.assertEquals(blockX, row.blockX());
        Assertions.assertEquals(blockY, row.blockY());
        Assertions.assertEquals(blockZ, row.blockZ());
        Assertions.assertEquals(playerUuid, row.playerUuid());
        Assertions.assertEquals(retentionPolicy, row.retentionPolicy());
        Assertions.assertEquals(expiresAtMillis, row.expiresAtMillis());
        Assertions.assertArrayEquals(payload, row.payload());
    }

    private record StoredEvent(
        int payloadGenerationId,
        Key eventType,
        int generation,
        long occurredAtMillis,
        int serverId,
        Key serverKey,
        Integer worldId,
        Key worldKey,
        Integer blockX,
        Integer blockY,
        Integer blockZ,
        String playerUuid,
        int retentionPolicyId,
        Key retentionPolicy,
        long expiresAtMillis,
        byte[] payload
    ) {
    }
}
