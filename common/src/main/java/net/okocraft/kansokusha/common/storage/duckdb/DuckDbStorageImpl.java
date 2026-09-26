package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import net.okocraft.kansokusha.common.storage.Storage;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBDriver;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * DuckDB-backed storage implementation loaded in an isolated class loader.
 */
@NotNullByDefault
public final class DuckDbStorageImpl implements Storage {

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

    // Bounds the cache in case a platform creates worlds with unique keys indefinitely.
    private static final int MAX_CACHED_KEYS = 1024;

    private final DuckDBConnection connection;
    // Key#asString() concatenates on every call, and the appender encodes strings to UTF-8 on every call.
    private final Map<Key, byte[]> encodedKeys = new HashMap<>();
    private @Nullable DuckDBAppender appender;

    private DuckDbStorageImpl(DuckDBConnection connection) {
        this.connection = connection;
    }

    public static DuckDbStorageImpl open(Path filepath) throws IOException, SQLException {
        var absolutePath = filepath.toAbsolutePath();
        Files.createDirectories(absolutePath.getParent());

        var connection = (DuckDBConnection) new DuckDBDriver().connect(
            "jdbc:duckdb:" + absolutePath,
            new Properties()
        );
        try (var statement = connection.createStatement()) {
            statement.execute(CREATE_EVENTS_TABLE);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        connection.setAutoCommit(false);
        return new DuckDbStorageImpl(connection);
    }

    @Override
    public void append(List<QueuedEvent> events) throws SQLException {
        try {
            var appender = this.appender();
            for (var queued : events) {
                var event = queued.submission();
                appender.beginRow()
                    .append(this.encode(event.eventType()))
                    .append(event.payloadGeneration().value())
                    .appendEpochMillis(queued.occurredAtMillis());
                this.appendNullable(appender, event.serverKey());
                this.appendNullable(appender, event.worldKey());

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

                appender.appendEpochMillis(queued.expiresAtMillis())
                    .append(event.payload().unsafeBytes())
                    .endRow();
            }
            appender.flush();
            this.connection.commit();
        } catch (SQLException | RuntimeException e) {
            this.discardAppender(e);
            this.rollback(e);
            throw e;
        }
    }

    @Override
    public int deleteExpired(Instant now) throws SQLException {
        try (var statement = this.connection.prepareStatement(
            "DELETE FROM events WHERE expires_at <= epoch_ms(?)"
        )) {
            statement.setLong(1, now.toEpochMilli());
            var deleted = statement.executeUpdate();
            this.connection.commit();
            return deleted;
        } catch (SQLException | RuntimeException e) {
            this.rollback(e);
            throw e;
        }
    }

    private DuckDBAppender appender() throws SQLException {
        var appender = this.appender;
        if (appender == null) {
            appender = this.connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "events");
            this.appender = appender;
        }
        return appender;
    }

    private void appendNullable(DuckDBAppender appender, @Nullable Key key) throws SQLException {
        if (key == null) {
            appender.appendNull();
        } else {
            appender.append(this.encode(key));
        }
    }

    private byte[] encode(Key key) {
        var encoded = this.encodedKeys.get(key);
        if (encoded == null) {
            if (this.encodedKeys.size() >= MAX_CACHED_KEYS) {
                this.encodedKeys.clear();
            }
            encoded = key.asString().getBytes(StandardCharsets.UTF_8);
            this.encodedKeys.put(key, encoded);
        }
        return encoded;
    }

    private void discardAppender(Exception failure) {
        var appender = this.appender;
        this.appender = null;
        if (appender == null) {
            return;
        }

        try {
            appender.close();
        } catch (SQLException e) {
            failure.addSuppressed(e);
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
        SQLException failure = null;
        var appender = this.appender;
        this.appender = null;

        if (appender != null) {
            try {
                appender.close();
            } catch (SQLException e) {
                failure = e;
            }
        }

        try {
            this.connection.close();
        } catch (SQLException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }

        if (failure != null) {
            throw failure;
        }
    }
}
