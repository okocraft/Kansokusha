package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.NotNullByDefault;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@NotNullByDefault
public final class DuckDbRetentionCleaner {

    private static final long MIN_FINITE_TIMESTAMP_MILLIS = -Long.MAX_VALUE + 1;
    private static final long MAX_FINITE_TIMESTAMP_MILLIS = Long.MAX_VALUE - 1;

    private final DuckDbDatabase database;
    private final AfterDelete afterDelete;

    public DuckDbRetentionCleaner(DuckDbDatabase database) {
        this(database, (connection, deletedRows) -> {
        });
    }

    DuckDbRetentionCleaner(DuckDbDatabase database, AfterDelete afterDelete) {
        this.database = Objects.requireNonNull(database, "database");
        this.afterDelete = Objects.requireNonNull(afterDelete, "afterDelete");
    }

    public int deleteExpired(Instant cutoff, int maxRowsPerPass) throws SQLException {
        Objects.requireNonNull(cutoff, "cutoff");
        if (maxRowsPerPass <= 0) {
            throw new IllegalArgumentException("maxRowsPerPass must be positive.");
        }

        var cutoffMillis = finiteMillis(cutoff.truncatedTo(ChronoUnit.MILLIS));
        return this.database.transaction(
            connection -> this.deleteExpired(connection, cutoffMillis, maxRowsPerPass)
        );
    }

    private int deleteExpired(Connection connection, long cutoffMillis, int maxRowsPerPass)
        throws SQLException {
        var rowIds = selectExpiredRowIds(connection, cutoffMillis, maxRowsPerPass);
        if (rowIds.isEmpty()) {
            return 0;
        }

        var deletedRows = deleteRows(connection, rowIds);
        if (deletedRows != rowIds.size()) {
            throw new SQLException(
                "Expected to delete " + rowIds.size() + " expired rows, but deleted " + deletedRows + "."
            );
        }

        this.afterDelete.accept(connection, deletedRows);
        return deletedRows;
    }

    private static List<Long> selectExpiredRowIds(
        Connection connection,
        long cutoffMillis,
        int maxRowsPerPass
    ) throws SQLException {
        var rowIds = new ArrayList<Long>(maxRowsPerPass);
        try (var statement = connection.prepareStatement(
            """
                SELECT rowid
                FROM events
                WHERE expires_at <= epoch_ms(?)
                LIMIT ?
                """
        )) {
            statement.setLong(1, cutoffMillis);
            statement.setInt(2, maxRowsPerPass);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    rowIds.add(rows.getLong(1));
                }
            }
        }
        return rowIds;
    }

    private static int deleteRows(Connection connection, List<Long> rowIds) throws SQLException {
        var placeholders = String.join(", ", java.util.Collections.nCopies(rowIds.size(), "?"));
        try (var statement = connection.prepareStatement(
            "DELETE FROM events WHERE rowid IN (" + placeholders + ")"
        )) {
            for (var index = 0; index < rowIds.size(); index++) {
                statement.setLong(index + 1, rowIds.get(index));
            }
            return statement.executeUpdate();
        }
    }

    private static long finiteMillis(Instant instant) throws SQLException {
        final long millis;
        try {
            millis = instant.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new SQLException("cutoff is outside the DuckDB TIMESTAMP_MS range", e);
        }

        if (millis < MIN_FINITE_TIMESTAMP_MILLIS || millis > MAX_FINITE_TIMESTAMP_MILLIS) {
            throw new SQLException("cutoff resolves to a DuckDB TIMESTAMP_MS infinity sentinel");
        }
        return millis;
    }

    @FunctionalInterface
    interface AfterDelete {

        void accept(Connection connection, int deletedRows) throws SQLException;
    }
}
