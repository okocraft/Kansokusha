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
import java.util.List;
import java.util.UUID;

class DuckDbEventWriterTest {

    private static final Key EVENT_A = Key.key("example", "block_break");
    private static final Key EVENT_B = Key.key("example", "block_place");
    private static final Key SERVER_A = Key.key("example", "survival");
    private static final Key SERVER_B = Key.key("example", "creative");
    private static final Key WORLD = Key.key("minecraft", "overworld");
    private static final Key SHORT = Key.key("example", "short");
    private static final Key AUDIT = Key.key("example", "audit");
    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testBatchRoundTrip(@TempDir Path dir) throws Exception {
        try (var database = open(dir.resolve("events.duckdb"))) {
            var writer = new DuckDbEventWriter(database);
            var first = event(
                EVENT_A, 1, "2026-01-02T03:04:05.123999999Z", SERVER_A,
                null, null, null, SHORT, "2026-01-02T04:04:05.123Z", 1
            );
            var second = event(
                EVENT_B, 2, "2026-01-02T03:05:06.456789123Z", SERVER_B,
                WORLD, new BlockPosition(10, 64, -3), new PlayerSubject(PLAYER),
                AUDIT, "2026-02-01T03:05:06.456Z", 2
            );

            Assertions.assertEquals(2, writer.append(List.of(first, second)));
            Assertions.assertEquals(2, count(database.connection(), "events"));

            try (var statement = database.connection().createStatement();
                 var rows = statement.executeQuery(
                     """
                         SELECT
                             et.event_type_key, pg.generation, epoch_ms(e.occurred_at) occurred_ms,
                             s.server_key, w.world_key, e.block_x, e.block_y, e.block_z,
                             CAST(e.subject_player_uuid AS VARCHAR) player_uuid,
                             rp.retention_policy_key, epoch_ms(e.expires_at) expires_ms, e.payload,
                             e.payload_generation_id, e.server_id, e.world_id, e.retention_policy_id
                         FROM events e
                         JOIN payload_generations pg ON pg.id = e.payload_generation_id
                         JOIN event_types et ON et.id = pg.event_type_id
                         JOIN servers s ON s.id = e.server_id
                         LEFT JOIN worlds w ON w.id = e.world_id
                         JOIN retention_policies rp ON rp.id = e.retention_policy_id
                         ORDER BY e.occurred_at
                         """
                 )) {
                Assertions.assertTrue(rows.next());
                Assertions.assertEquals(EVENT_A.asString(), rows.getString("event_type_key"));
                Assertions.assertEquals(1, rows.getInt("generation"));
                Assertions.assertEquals(
                    Instant.parse("2026-01-02T03:04:05.123Z").toEpochMilli(),
                    rows.getLong("occurred_ms")
                );
                Assertions.assertEquals(SERVER_A.asString(), rows.getString("server_key"));
                Assertions.assertNull(rows.getString("world_key"));
                Assertions.assertNull(rows.getObject("block_x"));
                Assertions.assertNull(rows.getString("player_uuid"));
                Assertions.assertEquals(SHORT.asString(), rows.getString("retention_policy_key"));
                Assertions.assertEquals(first.expiresAt().toEpochMilli(), rows.getLong("expires_ms"));
                Assertions.assertArrayEquals(new byte[]{1}, rows.getBytes("payload"));
                assertPositiveIds(rows);

                Assertions.assertTrue(rows.next());
                Assertions.assertEquals(EVENT_B.asString(), rows.getString("event_type_key"));
                Assertions.assertEquals(2, rows.getInt("generation"));
                Assertions.assertEquals(
                    Instant.parse("2026-01-02T03:05:06.456Z").toEpochMilli(),
                    rows.getLong("occurred_ms")
                );
                Assertions.assertEquals(SERVER_B.asString(), rows.getString("server_key"));
                Assertions.assertEquals(WORLD.asString(), rows.getString("world_key"));
                Assertions.assertEquals(10, rows.getInt("block_x"));
                Assertions.assertEquals(64, rows.getInt("block_y"));
                Assertions.assertEquals(-3, rows.getInt("block_z"));
                Assertions.assertEquals(PLAYER.toString(), rows.getString("player_uuid"));
                Assertions.assertEquals(AUDIT.asString(), rows.getString("retention_policy_key"));
                Assertions.assertEquals(second.expiresAt().toEpochMilli(), rows.getLong("expires_ms"));
                Assertions.assertArrayEquals(new byte[]{2}, rows.getBytes("payload"));
                assertPositiveIds(rows);
                Assertions.assertFalse(rows.next());
            }
        }
    }

    @Test
    void testFailureRollsBackWrittenBatchAndMetadata(@TempDir Path dir) throws Exception {
        try (var database = open(dir.resolve("rollback.duckdb"))) {
            var committed = event(
                EVENT_A, 1, "2026-01-01T00:00:00Z", SERVER_A,
                null, null, null, SHORT, "2026-01-01T01:00:00Z", 1
            );
            Assertions.assertEquals(1, new DuckDbEventWriter(database).append(List.of(committed)));

            var failing = new DuckDbEventWriter(database, (connection, count) -> {
                Assertions.assertEquals(2, count);
                Assertions.assertEquals(3, count(connection, "events"));
                throw new SQLException("injected write failure");
            });

            var failed = List.of(
                event(
                    Key.key("failed", "one"), 1, "2026-01-02T00:00:00Z",
                    Key.key("failed", "server"), Key.key("failed", "world"),
                    new BlockPosition(1, 2, 3), null, Key.key("failed", "retention"),
                    "2026-01-03T00:00:00Z", 2
                ),
                event(
                    Key.key("failed", "two"), 1, "2026-01-02T00:00:01Z",
                    Key.key("failed", "server"), null, null, null,
                    Key.key("failed", "retention"), "2026-01-03T00:00:01Z", 3
                )
            );

            Assertions.assertThrows(SQLException.class, () -> failing.append(failed));
            Assertions.assertEquals(1, count(database.connection(), "events"));
            Assertions.assertEquals(1, count(database.connection(), "event_types"));
            Assertions.assertEquals(1, count(database.connection(), "payload_generations"));
            Assertions.assertEquals(1, count(database.connection(), "servers"));
            Assertions.assertEquals(0, count(database.connection(), "worlds"));
            Assertions.assertEquals(1, count(database.connection(), "retention_policies"));

            try (var statement = database.connection().createStatement();
                 var rows = statement.executeQuery(
                     """
                         SELECT et.event_type_key, e.payload
                         FROM events e
                         JOIN payload_generations pg ON pg.id = e.payload_generation_id
                         JOIN event_types et ON et.id = pg.event_type_id
                         """
                 )) {
                Assertions.assertTrue(rows.next());
                Assertions.assertEquals(EVENT_A.asString(), rows.getString("event_type_key"));
                Assertions.assertArrayEquals(new byte[]{1}, rows.getBytes("payload"));
                Assertions.assertFalse(rows.next());
            }
        }
    }

    @Test
    void testWorldIdentityIsServerScopedAndEmptyBatchIsNoOp(@TempDir Path dir) throws Exception {
        try (var database = open(dir.resolve("scope.duckdb"))) {
            var writer = new DuckDbEventWriter(database);
            var events = List.of(
                event(
                    EVENT_A, 1, "2026-01-01T00:00:00Z", SERVER_A,
                    WORLD, null, null, SHORT, "2026-01-01T01:00:00Z", 1
                ),
                event(
                    EVENT_B, 1, "2026-01-01T00:00:01Z", SERVER_B,
                    WORLD, null, null, SHORT, "2026-01-01T01:00:01Z", 2
                )
            );

            Assertions.assertEquals(2, writer.append(events));
            Assertions.assertEquals(2, count(database.connection(), "worlds"));
            Assertions.assertEquals(0, writer.append(List.of()));
            Assertions.assertEquals(2, count(database.connection(), "events"));
        }
    }

    private static DuckDbDatabase open(Path file) throws Exception {
        var database = DuckDbDatabase.open(file);
        DuckDbMigrations.migrate(database.connection());
        return database;
    }

    private static AcceptedEvent event(
        Key eventType,
        int generation,
        String occurredAt,
        Key server,
        Key world,
        BlockPosition position,
        PlayerSubject subject,
        Key retention,
        String expiresAt,
        int payload
    ) {
        return new AcceptedEvent(
            new EventSubmission(
                eventType, new PayloadGeneration(generation), Instant.parse(occurredAt), server,
                world, position, subject, EventPayload.copyOf(new byte[]{(byte) payload})
            ),
            retention,
            Instant.parse(expiresAt)
        );
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void assertPositiveIds(java.sql.ResultSet rows) throws SQLException {
        Assertions.assertTrue(rows.getInt("payload_generation_id") > 0);
        Assertions.assertTrue(rows.getInt("server_id") > 0);
        Assertions.assertTrue(rows.getInt("retention_policy_id") > 0);
    }
}
