package net.okocraft.kansokusha.common.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

class DuckDbMigrationRunnerTest {

    private static final DuckDbMigration V1 = DuckDbMigration.of(
        1,
        "create_fixture",
        "CREATE TABLE fixture_data (value INTEGER NOT NULL)"
    );

    private static final DuckDbMigration V2 = DuckDbMigration.of(
        2,
        "add_fixture_label",
        "ALTER TABLE fixture_data ADD COLUMN label VARCHAR DEFAULT 'kept'"
    );

    @Test
    void testFreshDatabaseAppliesAllMigrationsInOrder(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("fresh.duckdb"))) {
            DuckDbMigrationRunner.of(V1, V2).migrate(database.connection());

            Assertions.assertEquals(2, migrationCount(database.connection()));
            Assertions.assertEquals("add_fixture_label", migrationName(database.connection(), 2));
        }
    }

    @Test
    void testReopenDoesNotReapplyMigration(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("reopen.duckdb");
        var runner = DuckDbMigrationRunner.of(V1);

        try (var database = DuckDbDatabase.open(filepath)) {
            runner.migrate(database.connection());
        }

        try (var database = DuckDbDatabase.open(filepath)) {
            runner.migrate(database.connection());
            Assertions.assertEquals(1, migrationCount(database.connection()));
        }
    }

    @Test
    void testOlderEventFixtureUpgradesWithoutLosingValidEventData(@TempDir Path dir)
        throws Exception {
        var eventV1 = DuckDbMigration.of(
            1,
            "event_fixture_v1",
            "CREATE TABLE fixture_events (event_type VARCHAR NOT NULL, payload BLOB NOT NULL)"
        );
        var eventV2 = DuckDbMigration.of(
            2,
            "event_fixture_v2",
            "ALTER TABLE fixture_events ADD COLUMN generation INTEGER DEFAULT 1"
        );
        var filepath = dir.resolve("event-fixture-upgrade.duckdb");

        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrationRunner.of(eventV1).migrate(database.connection());
            try (var statement = database.connection().prepareStatement(
                "INSERT INTO fixture_events (event_type, payload) VALUES (?, ?)"
            )) {
                statement.setString(1, "example:legacy");
                statement.setBytes(2, new byte[]{1, 2, 3});
                Assertions.assertEquals(1, statement.executeUpdate());
            }
        }

        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrationRunner.of(eventV1, eventV2).migrate(database.connection());
            try (var statement = database.connection().createStatement();
                 var rows = statement.executeQuery(
                     "SELECT event_type, hex(payload) payload_hex, generation FROM fixture_events"
                 )) {
                Assertions.assertTrue(rows.next());
                Assertions.assertEquals("example:legacy", rows.getString("event_type"));
                Assertions.assertEquals("010203", rows.getString("payload_hex"));
                Assertions.assertEquals(1, rows.getInt("generation"));
                Assertions.assertFalse(rows.next());
            }
            Assertions.assertEquals(2, migrationCount(database.connection()));
        }
    }

    @Test
    void testOlderDatabaseUpgradesWithoutLosingData(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("upgrade.duckdb");

        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrationRunner.of(V1).migrate(database.connection());
            execute(database.connection(), "INSERT INTO fixture_data (value) VALUES (42)");
        }

        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrationRunner.of(V1, V2).migrate(database.connection());

            try (var statement = database.connection().createStatement();
                 var result = statement.executeQuery(
                     "SELECT value, label FROM fixture_data"
                 )) {
                Assertions.assertTrue(result.next());
                Assertions.assertEquals(42, result.getInt("value"));
                Assertions.assertEquals("kept", result.getString("label"));
                Assertions.assertFalse(result.next());
            }

            Assertions.assertEquals(2, migrationCount(database.connection()));
        }
    }

    @Test
    void testRejectsRecordedNameMismatch(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("name-mismatch.duckdb"))) {
            DuckDbMigrationRunner.of(V1).migrate(database.connection());

            var changedName = DuckDbMigration.of(
                1,
                "renamed_fixture",
                "CREATE TABLE fixture_data (value INTEGER NOT NULL)"
            );

            var exception = Assertions.assertThrows(
                SQLException.class,
                () -> DuckDbMigrationRunner.of(changedName).migrate(database.connection())
            );

            Assertions.assertTrue(exception.getMessage().contains("name mismatch"));
        }
    }

    @Test
    void testRejectsRecordedChecksumMismatch(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("checksum-mismatch.duckdb"))) {
            DuckDbMigrationRunner.of(V1).migrate(database.connection());

            var changedSql = DuckDbMigration.of(
                1,
                V1.name(),
                "CREATE TABLE fixture_data (value BIGINT NOT NULL)"
            );

            var exception = Assertions.assertThrows(
                SQLException.class,
                () -> DuckDbMigrationRunner.of(changedSql).migrate(database.connection())
            );

            Assertions.assertTrue(exception.getMessage().contains("checksum mismatch"));
        }
    }

    @Test
    void testRejectsUnknownNewerMigration(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("newer.duckdb"))) {
            DuckDbMigrationRunner.of(V1, V2).migrate(database.connection());

            var exception = Assertions.assertThrows(
                SQLException.class,
                () -> DuckDbMigrationRunner.of(V1).migrate(database.connection())
            );

            Assertions.assertTrue(exception.getMessage().contains("newer migration version 2"));
        }
    }

    @Test
    void testRejectsGapInRecordedHistory(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("gap.duckdb"))) {
            new DuckDbMigrationRunner(List.of()).migrate(database.connection());

            try (var statement = database.connection().prepareStatement(
                "INSERT INTO schema_migrations (version, name, checksum) VALUES (2, ?, ?)"
            )) {
                statement.setString(1, V2.name());
                statement.setString(2, V2.checksum());
                statement.executeUpdate();
            }

            var exception = Assertions.assertThrows(
                SQLException.class,
                () -> DuckDbMigrationRunner.of(V1, V2).migrate(database.connection())
            );

            Assertions.assertTrue(exception.getMessage().contains("expected recorded version 1"));
        }
    }

    @Test
    void testFailedMigrationRollsBackSchemaAndHistory(@TempDir Path dir) throws Exception {
        var failingV2 = DuckDbMigration.of(
            2,
            "failing_change",
            "CREATE TABLE rolled_back_table (value INTEGER)",
            "INSERT INTO missing_table VALUES (1)"
        );

        try (var database = DuckDbDatabase.open(dir.resolve("rollback.duckdb"))) {
            var exception = Assertions.assertThrows(
                SQLException.class,
                () -> DuckDbMigrationRunner.of(V1, failingV2).migrate(database.connection())
            );

            Assertions.assertTrue(exception.getMessage().contains("failing_change"));
            Assertions.assertEquals(1, migrationCount(database.connection()));
            Assertions.assertFalse(tableExists(database.connection(), "rolled_back_table"));
            Assertions.assertTrue(tableExists(database.connection(), "fixture_data"));
        }
    }

    @Test
    void testRejectsTransactionControlAndMultipleStatements() {
        for (var sql : List.of(
            "COMMIT",
            "  begin transaction",
            "-- comment\nROLLBACK",
            "/* block */ END",
            "CREATE TABLE t (value INTEGER); COMMIT"
        )) {
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DuckDbMigration.of(1, "invalid", sql),
                sql
            );
        }

        Assertions.assertDoesNotThrow(() -> DuckDbMigration.of(
            1,
            "valid",
            "CREATE TABLE t (value INTEGER);",
            "SELECT CASE WHEN 1 = 1 THEN 1 END",
            "CREATE TABLE commits (value INTEGER)"
        ));
    }

    @Test
    void testRejectsNonConsecutiveApplicationDefinitions() {
        var versionTwo = DuckDbMigration.of(2, "version_two", "SELECT 1");

        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> DuckDbMigrationRunner.of(versionTwo)
        );
    }

    private static int migrationCount(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM schema_migrations")) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String migrationName(Connection connection, int version) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT name FROM schema_migrations WHERE version = ?"
        )) {
            statement.setInt(1, version);
            try (var result = statement.executeQuery()) {
                Assertions.assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'main' AND table_name = ?"
        )) {
            statement.setString(1, tableName);
            try (var result = statement.executeQuery()) {
                Assertions.assertTrue(result.next());
                return result.getInt(1) == 1;
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
