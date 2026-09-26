package net.okocraft.kansokusha.common.storage;

import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBDriver;
import org.duckdb.DuckDBAppender;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

/**
 * Owns the DuckDB connection. Callers must not use one instance from multiple threads at the same time.
 */
@NotNullByDefault
public final class DuckDbStorage implements AutoCloseable {

    // DuckDB compresses repeated strings with dictionary compression, so keys are stored as-is
    // instead of being normalized into lookup tables.
    private static final String CREATE_EVENTS_TABLE = """
        CREATE TABLE IF NOT EXISTS events (
            event_type VARCHAR NOT NULL,
            payload_generation INTEGER NOT NULL,
            occurred_at TIMESTAMP_MS NOT NULL,
            server VARCHAR,
            world VARCHAR,
            x INTEGER,
            y INTEGER,
            z INTEGER,
            player UUID,
            expires_at TIMESTAMP_MS NOT NULL,
            payload BLOB NOT NULL
        )
        """;

    private final DuckDBConnection connection;

    private DuckDbStorage(DuckDBConnection connection) {
        this.connection = connection;
    }

    public static DuckDbStorage open(Path filepath) throws IOException, SQLException {
        var absolutePath = filepath.toAbsolutePath();
        Files.createDirectories(absolutePath.getParent());

        // Use the bundled driver directly because DriverManager may not see it from a plugin class loader.
        var connection = (DuckDBConnection) new DuckDBDriver().connect("jdbc:duckdb:" + absolutePath, new Properties());
        try (var statement = connection.createStatement()) {
            statement.execute(CREATE_EVENTS_TABLE);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        connection.setAutoCommit(false);
        return new DuckDbStorage(connection);
    }

    public void append(List<EventSubmission> events, KansokushaConfig.Retention retention) throws SQLException {
        try (var appender = this.connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "events")) {
            for (var event : events) {
                var occurredAt = event.occurredAt().toEpochMilli();
                var expiresAt = event.occurredAt().plus(retention.durationOf(event.eventType())).toEpochMilli();

                appender.beginRow()
                    .append(event.eventType().asString())
                    .append(event.payloadGeneration().value())
                    .appendEpochMillis(occurredAt);
                appendNullable(appender, event.serverKey() == null ? null : event.serverKey().asString());
                appendNullable(appender, event.worldKey() == null ? null : event.worldKey().asString());

                var position = event.position();
                if (position == null) {
                    appender.appendNull().appendNull().appendNull();
                } else {
                    appender.append(position.x()).append(position.y()).append(position.z());
                }

                if (event.subject() instanceof PlayerSubject player) {
                    appender.append(player.uniqueId());
                } else {
                    appender.appendNull();
                }

                appender.appendEpochMillis(expiresAt)
                    .append(event.payload().copyBytes())
                    .endRow();
            }
            appender.flush();
            this.connection.commit();
        } catch (SQLException | RuntimeException e) {
            this.rollback(e);
            throw e;
        }
    }

    public int deleteExpired(Instant now) throws SQLException {
        try (var statement = this.connection.prepareStatement("DELETE FROM events WHERE expires_at <= epoch_ms(?)")) {
            statement.setLong(1, now.toEpochMilli());
            var deleted = statement.executeUpdate();
            this.connection.commit();
            return deleted;
        } catch (SQLException | RuntimeException e) {
            this.rollback(e);
            throw e;
        }
    }

    private static void appendNullable(DuckDBAppender appender, @Nullable String value) throws SQLException {
        if (value == null) {
            appender.appendNull();
        } else {
            appender.append(value);
        }
    }

    private void rollback(Exception failure) {
        try {
            this.connection.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    @Override
    public void close() throws SQLException {
        this.connection.close();
    }
}
