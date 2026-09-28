package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.common.id.TimeBasedUUID;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

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
            payload BLOB NOT NULL,
            search_text VARCHAR
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
        "payload",
        "search_text"
    );

    private static final String CREATE_PLAYER_NAMES_TABLE = """
        CREATE TABLE IF NOT EXISTS player_names (
            player_uuid UUID NOT NULL,
            name VARCHAR NOT NULL,
            last_seen TIMESTAMP_MS NOT NULL,
            PRIMARY KEY (player_uuid, name)
        )
        """;

    private static final List<String> PLAYER_NAMES_COLUMNS = List.of(
        "player_uuid",
        "name",
        "last_seen"
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
            verifyColumns(statement, "events", EVENTS_COLUMNS);
            statement.execute(CREATE_PLAYER_NAMES_TABLE);
            verifyColumns(statement, "player_names", PLAYER_NAMES_COLUMNS);
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
                this.appendEvent(appender, queued);
            }
            appender.flush();
            this.recordPlayerNames(events);
            this.connection.commit();
        } catch (SQLException | RuntimeException e) {
            this.discardAppender(e);
            this.rollback(e);
            throw e;
        }
    }

    private void appendEvent(DuckDBAppender appender, QueuedEvent queued) throws SQLException {
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
            .append(event.payload().unsafeBytes());
        var searchText = queued.searchText();
        if (searchText == null) {
            appender.appendNull();
        } else {
            appender.append(searchText);
        }
        appender.endRow();
    }

    private void recordPlayerNames(List<QueuedEvent> events) throws SQLException {
        try (var statement = this.connection.prepareStatement("""
            INSERT INTO player_names (player_uuid, name, last_seen)
            VALUES (?, ?, epoch_ms(?))
            ON CONFLICT DO UPDATE SET last_seen = greatest(last_seen, excluded.last_seen)
            """)) {
            for (var queued : events) {
                var name = queued.playerName();
                if (name != null && queued.submission().actor() instanceof PlayerActor player) {
                    statement.setObject(1, player.uniqueId());
                    statement.setString(2, name);
                    statement.setLong(3, queued.occurredAtMillis());
                    statement.executeUpdate();
                }
            }
        }
    }

    @Override
    public List<String> offlinePlayerNames() throws SQLException {
        var names = new ArrayList<String>();
        try (var statement = this.connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT arg_max(name, last_seen)
                 FROM player_names
                 GROUP BY lower(name)
                 ORDER BY lower(name)
                 """)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return List.copyOf(names);
    }

    @Override
    public Optional<EventDetail> findEvent(UUID eventId) throws SQLException {
        return DuckDbEventSearch.find(this.connection, Objects.requireNonNull(eventId, "eventId"));
    }

    @Override
    public SearchPage search(SearchRequest request) throws SQLException {
        return DuckDbEventSearch.search(this.connection, request);
    }

    @Override
    public SearchMetadata searchMetadata() throws SQLException {
        var eventTypes = new LinkedHashSet<Key>();
        var worlds = new LinkedHashMap<Key, LinkedHashSet<Key>>();
        var actorTypes = new LinkedHashMap<Key, LinkedHashSet<Key>>();
        var targetTypes = new LinkedHashMap<Key, LinkedHashSet<Key>>();

        try (var statement = this.connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT event_type, category, value
                 FROM (
                     SELECT DISTINCT event_type, 'event' AS category, NULL AS value FROM events
                     UNION
                     SELECT DISTINCT event_type, 'world', world
                     FROM events
                     WHERE world IS NOT NULL
                     UNION
                     SELECT DISTINCT event_type, 'actor', actor_type
                     FROM events
                     WHERE actor_type IS NOT NULL
                     UNION
                     SELECT DISTINCT event_type, 'target', target_type
                     FROM events
                     WHERE target_type IS NOT NULL
                 )
                 ORDER BY event_type, category, value
                 """)) {
            while (rows.next()) {
                var eventType = Key.key(rows.getString("event_type"));
                eventTypes.add(eventType);
                var value = rows.getString("value");
                switch (rows.getString("category")) {
                    case "event" -> {
                        // The event row keeps types with no world/actor/target metadata visible.
                    }
                    case "world" -> metadataValues(worlds, eventType).add(Key.key(value));
                    case "actor" -> metadataValues(actorTypes, eventType).add(Key.key(value));
                    case "target" -> metadataValues(targetTypes, eventType).add(Key.key(value));
                    default -> throw new SQLException("Unexpected search metadata category.");
                }
            }
        }

        var events = new LinkedHashMap<Key, SearchMetadata.EventValues>();
        for (var eventType : eventTypes) {
            events.put(
                eventType,
                new SearchMetadata.EventValues(
                    Set.copyOf(worlds.getOrDefault(eventType, new LinkedHashSet<>())),
                    Set.copyOf(actorTypes.getOrDefault(eventType, new LinkedHashSet<>())),
                    Set.copyOf(targetTypes.getOrDefault(eventType, new LinkedHashSet<>()))
                )
            );
        }
        return new SearchMetadata(events);
    }

    private static LinkedHashSet<Key> metadataValues(
        Map<Key, LinkedHashSet<Key>> values,
        Key eventType
    ) {
        return values.computeIfAbsent(eventType, ignored -> new LinkedHashSet<>());
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

    private static void verifyColumns(
        Statement statement,
        String tableName,
        List<String> expectedColumns
    ) throws SQLException {
        var columns = new ArrayList<String>();
        try (var rows = statement.executeQuery(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = '" + tableName + "' "
                + "ORDER BY ordinal_position"
        )) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        if (!columns.equals(expectedColumns)) {
            throw new SQLException(
                "The " + tableName + " table has unsupported columns " + columns
                    + " (expected " + expectedColumns
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
