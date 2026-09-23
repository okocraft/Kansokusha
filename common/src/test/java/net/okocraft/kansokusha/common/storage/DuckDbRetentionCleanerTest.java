package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
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

class DuckDbRetentionCleanerTest {

    private static final Key EVENT_TYPE = Key.key("example", "retention-test");
    private static final Key SERVER = Key.key("example", "survival");
    private static final Key RETENTION = Key.key("example", "short");

    @Test
    void testExpiryBoundaryUsesMillisecondCutoff(@TempDir Path dir) throws Exception {
        try (var database = open(dir.resolve("boundary.duckdb"))) {
            var writer = new DuckDbEventWriter(database);
            writer.append(List.of(
                event(1, "2026-01-01T23:59:59.999Z"),
                event(2, "2026-01-02T00:00:00.000Z"),
                event(3, "2026-01-02T00:00:00.001Z")
            ));

            var cleaner = new DuckDbRetentionCleaner(database);
            var cutoff = Instant.parse("2026-01-02T00:00:00.000999999Z");

            Assertions.assertEquals(2, cleaner.deleteExpired(cutoff, 10));
            Assertions.assertEquals(List.of("03"), payloads(database.connection()));
        }
    }

    @Test
    void testRepeatedBoundedPassesEventuallyDeleteExpiredRows(@TempDir Path dir) throws Exception {
        try (var database = open(dir.resolve("bounded.duckdb"))) {
            var events = new ArrayList<AcceptedEvent>();
            for (var payload = 1; payload <= 5; payload++) {
                events.add(event(payload, "2026-01-01T00:00:00Z"));
            }
            events.add(event(6, "2026-01-03T00:00:00Z"));
            new DuckDbEventWriter(database).append(events);

            var cleaner = new DuckDbRetentionCleaner(database);
            var cutoff = Instant.parse("2026-01-02T00:00:00Z");

            Assertions.assertEquals(2, cleaner.deleteExpired(cutoff, 2));
            Assertions.assertEquals(4, count(database.connection(), "events"));
            Assertions.assertEquals(2, cleaner.deleteExpired(cutoff, 2));
            Assertions.assertEquals(2, count(database.connection(), "events"));
            Assertions.assertEquals(1, cleaner.deleteExpired(cutoff, 2));
            Assertions.assertEquals(1, count(database.connection(), "events"));
            Assertions.assertEquals(0, cleaner.deleteExpired(cutoff, 2));
            Assertions.assertEquals(List.of("06"), payloads(database.connection()));
        }
    }

    @Test
    void testDeletionFailureRollsBackAndPropagates(@TempDir Path dir) throws Exception {
        try (var database = open(dir.resolve("rollback.duckdb"))) {
            new DuckDbEventWriter(database).append(List.of(
                event(1, "2026-01-01T00:00:00Z"),
                event(2, "2026-01-03T00:00:00Z")
            ));

            var cleaner = new DuckDbRetentionCleaner(database, (connection, deletedRows) -> {
                Assertions.assertEquals(1, deletedRows);
                Assertions.assertEquals(1, count(connection, "events"));
                throw new SQLException("injected deletion failure");
            });

            var failure = Assertions.assertThrows(
                SQLException.class,
                () -> cleaner.deleteExpired(Instant.parse("2026-01-02T00:00:00Z"), 10)
            );
            Assertions.assertEquals("injected deletion failure", failure.getMessage());
            Assertions.assertEquals(List.of("01", "02"), payloads(database.connection()));
        }
    }

    @Test
    void testNonPositivePassBoundIsRejected(@TempDir Path dir) throws Exception {
        try (var database = open(dir.resolve("invalid-bound.duckdb"))) {
            var cleaner = new DuckDbRetentionCleaner(database);
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> cleaner.deleteExpired(Instant.EPOCH, 0)
            );
        }
    }

    private static DuckDbDatabase open(Path file) throws Exception {
        var database = DuckDbDatabase.open(file);
        DuckDbMigrations.migrate(database);
        return database;
    }

    private static AcceptedEvent event(int payload, String expiresAt) {
        return new AcceptedEvent(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                Instant.parse("2026-01-01T00:00:00Z").plusMillis(payload),
                SERVER,
                null,
                null,
                null,
                EventPayload.copyOf(new byte[]{(byte) payload})
            ),
            RETENTION,
            Instant.parse(expiresAt)
        );
    }

    private static List<String> payloads(Connection connection) throws SQLException {
        var payloads = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT hex(payload) payload_hex FROM events ORDER BY occurred_at"
             )) {
            while (rows.next()) {
                payloads.add(rows.getString("payload_hex"));
            }
        }
        return payloads;
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
