package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.common.id.TimeBasedUUID;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import net.okocraft.kansokusha.common.storage.Storage;
import net.okocraft.kansokusha.common.storage.StorageHealth;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
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
            event_id UUID NOT NULL,
            event_type VARCHAR NOT NULL,
            payload_generation INTEGER NOT NULL,
            occurred_at TIMESTAMP_MS NOT NULL,
            server VARCHAR,
            world VARCHAR,
            x INTEGER,
            y INTEGER,
            z INTEGER,
            actor_kind VARCHAR,
            actor_uuid UUID,
            actor_type VARCHAR,
            target_type VARCHAR,
            expires_at TIMESTAMP_MS NOT NULL,
            payload BLOB NOT NULL
        )
        """;

    private static final List<String> EVENTS_COLUMNS = List.of(
        "event_id",
        "event_type",
        "payload_generation",
        "occurred_at",
        "server",
        "world",
        "x",
        "y",
        "z",
        "actor_kind",
        "actor_uuid",
        "actor_type",
        "target_type",
        "expires_at",
        "payload"
    );

    private static final byte[] PLAYER_ACTOR_KIND = "player".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENTITY_ACTOR_KIND = "entity".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOCK_ACTOR_KIND = "block".getBytes(StandardCharsets.UTF_8);

    // Bounds the cache in case a platform creates worlds with unique keys indefinitely.
    // Large enough to hold every block, item and entity type key used as actor or target types.
    private static final int MAX_CACHED_KEYS = 4096;

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
            verifyEventsColumns(statement);
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
                    .append(TimeBasedUUID.generate())
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

                this.appendActor(appender, event.actor());
                this.appendNullable(appender, event.targetType());

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

    @Override
    public void checkpoint() throws SQLException {
        try (var statement = this.connection.createStatement()) {
            statement.execute("CHECKPOINT");
        }
    }

    @Override
    public StorageHealth health() throws SQLException {
        try (var statement = this.connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT
                     (SELECT count(*) FROM events) AS event_count,
                     database_size,
                     block_size,
                     total_blocks,
                     used_blocks,
                     free_blocks,
                     wal_size
                 FROM pragma_database_size()
                 WHERE database_name = current_database()
                 """)) {
            if (!rows.next()) {
                throw new SQLException("DuckDB did not report database size information.");
            }
            return new StorageHealth(
                rows.getLong("event_count"),
                rows.getString("database_size"),
                rows.getLong("block_size"),
                rows.getLong("total_blocks"),
                rows.getLong("used_blocks"),
                rows.getLong("free_blocks"),
                rows.getString("wal_size")
            );
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

    private static void verifyEventsColumns(Statement statement) throws SQLException {
        var columns = new ArrayList<String>();
        try (var rows = statement.executeQuery(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = 'events' "
                + "ORDER BY ordinal_position"
        )) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        if (!columns.equals(EVENTS_COLUMNS)) {
            throw new SQLException(
                "The events table has unsupported columns " + columns + " (expected " + EVENTS_COLUMNS
                    + "). The database was created by an incompatible version of Kansokusha."
            );
        }
    }

    private void appendActor(DuckDBAppender appender, @Nullable EventActor actor) throws SQLException {
        switch (actor) {
            case null -> appender.appendNull().appendNull().appendNull();
            case PlayerActor player -> appender.append(PLAYER_ACTOR_KIND)
                .append(player.uniqueId())
                .appendNull();
            case EntityActor entity -> appender.append(ENTITY_ACTOR_KIND)
                .append(entity.uniqueId())
                .append(this.encode(entity.entityType()));
            case BlockActor block -> appender.append(BLOCK_ACTOR_KIND)
                .appendNull()
                .append(this.encode(block.blockType()));
        }
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
