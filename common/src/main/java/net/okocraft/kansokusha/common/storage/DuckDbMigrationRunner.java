package net.okocraft.kansokusha.common.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class DuckDbMigrationRunner {

    private static final String CREATE_HISTORY_TABLE = """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY CHECK (version > 0),
            name VARCHAR NOT NULL,
            checksum VARCHAR NOT NULL,
            applied_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
        )
        """;

    private final List<DuckDbMigration> migrations;

    public DuckDbMigrationRunner(List<DuckDbMigration> migrations) {
        Objects.requireNonNull(migrations, "migrations");
        this.migrations = List.copyOf(migrations);
        validateDefinitions(this.migrations);
    }

    public static DuckDbMigrationRunner of(DuckDbMigration... migrations) {
        Objects.requireNonNull(migrations, "migrations");
        return new DuckDbMigrationRunner(Arrays.asList(migrations));
    }

    public void migrate(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");

        if (!connection.getAutoCommit()) {
            throw new SQLException("Migration runner requires a connection in auto-commit mode.");
        }

        bootstrapHistoryTable(connection);
        var appliedCount = validateHistory(connection);

        for (int index = appliedCount; index < this.migrations.size(); index++) {
            applyMigration(connection, this.migrations.get(index));
        }
    }

    private static void validateDefinitions(List<DuckDbMigration> migrations) {
        for (int index = 0; index < migrations.size(); index++) {
            var migration = Objects.requireNonNull(migrations.get(index), "migration");
            var expectedVersion = index + 1;
            if (migration.version() != expectedVersion) {
                throw new IllegalArgumentException(
                    "Migration definitions must be a consecutive ordered list starting at version 1; expected "
                        + expectedVersion + " but found " + migration.version()
                );
            }
        }
    }

    private static void bootstrapHistoryTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(CREATE_HISTORY_TABLE);
        }
    }

    private int validateHistory(Connection connection) throws SQLException {
        var appliedCount = 0;

        try (var statement = connection.createStatement();
             var result = statement.executeQuery(
                 "SELECT version, name, checksum FROM schema_migrations ORDER BY version"
             )) {
            while (result.next()) {
                var version = result.getInt("version");
                var expectedVersion = appliedCount + 1;

                if (version != expectedVersion) {
                    throw invalidHistory(
                        "expected recorded version " + expectedVersion + " but found " + version
                    );
                }

                if (version > this.migrations.size()) {
                    throw invalidHistory(
                        "database contains newer migration version " + version
                            + " but application only knows through version " + this.migrations.size()
                    );
                }

                var expected = this.migrations.get(version - 1);
                var recordedName = result.getString("name");
                if (!expected.name().equals(recordedName)) {
                    throw invalidHistory(
                        "name mismatch for migration " + version + ": expected "
                            + expected.name() + " but found " + recordedName
                    );
                }

                var recordedChecksum = result.getString("checksum");
                if (!expected.checksum().equals(recordedChecksum)) {
                    throw invalidHistory("checksum mismatch for migration " + version);
                }

                appliedCount++;
            }
        }

        return appliedCount;
    }

    private static void applyMigration(Connection connection, DuckDbMigration migration) throws SQLException {
        beginTransaction(connection);

        try {
            try (var statement = connection.createStatement()) {
                for (var sql : migration.statements()) {
                    statement.execute(sql);
                }
            }

            try (var statement = connection.prepareStatement(
                "INSERT INTO schema_migrations (version, name, checksum) VALUES (?, ?, ?)"
            )) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.name());
                statement.setString(3, migration.checksum());
                statement.executeUpdate();
            }

            commitTransaction(connection);
        } catch (SQLException e) {
            try {
                rollbackTransaction(connection);
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }

            throw new SQLException(
                "Failed to apply DuckDB migration " + migration.version() + " (" + migration.name() + ").",
                e
            );
        }
    }

    private static void beginTransaction(Connection connection) throws SQLException {
        executeTransactionStatement(connection, "BEGIN TRANSACTION");
    }

    private static void commitTransaction(Connection connection) throws SQLException {
        executeTransactionStatement(connection, "COMMIT");
    }

    private static void rollbackTransaction(Connection connection) throws SQLException {
        executeTransactionStatement(connection, "ROLLBACK");
    }

    private static void executeTransactionStatement(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static SQLException invalidHistory(String detail) {
        return new SQLException("Invalid DuckDB migration history: " + detail);
    }
}
