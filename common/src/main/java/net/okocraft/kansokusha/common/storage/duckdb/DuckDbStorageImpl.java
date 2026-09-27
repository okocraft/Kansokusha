package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.id.TimeBasedUUID;
import net.okocraft.kansokusha.common.player.PlayerNameChangePayloadCodec;
import net.okocraft.kansokusha.common.player.PlayerNameDirectory;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
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

    private static final String CREATE_PLAYER_NAME_HISTORY_TABLE = """
        CREATE TABLE IF NOT EXISTS player_name_history (
            player_uuid UUID NOT NULL,
            name VARCHAR NOT NULL,
            normalized_name VARCHAR NOT NULL,
            first_seen TIMESTAMP_MS NOT NULL,
            last_seen TIMESTAMP_MS NOT NULL,
            last_event_id UUID NOT NULL
        )
        """;

    private static final List<String> PLAYER_NAME_HISTORY_COLUMNS = List.of(
        "player_uuid",
        "name",
        "normalized_name",
        "first_seen",
        "last_seen",
        "last_event_id"
    );

    private static final String CREATE_EVENT_SEARCH_TEXT_TABLE = """
        CREATE TABLE IF NOT EXISTS event_search_text (
            event_id UUID NOT NULL,
            search_text VARCHAR NOT NULL
        )
        """;

    private static final List<String> EVENT_SEARCH_TEXT_COLUMNS = List.of(
        "event_id",
        "search_text"
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
            statement.execute(CREATE_PLAYER_NAME_HISTORY_TABLE);
            verifyColumns(statement, "player_name_history", PLAYER_NAME_HISTORY_COLUMNS);
            statement.execute(CREATE_EVENT_SEARCH_TEXT_TABLE);
            verifyColumns(statement, "event_search_text", EVENT_SEARCH_TEXT_COLUMNS);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS event_search_text_event_id
                ON event_search_text(event_id)
                """);
            statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS player_name_history_uuid_name
                ON player_name_history(player_uuid, name)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS player_name_history_name_lookup
                ON player_name_history(normalized_name, last_seen, last_event_id)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS player_name_history_uuid_latest
                ON player_name_history(player_uuid, last_seen, last_event_id)
                """);
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
            var storedEvents = this.prepareStoredEvents(events);
            var appender = this.appender();
            for (var stored : storedEvents) {
                this.appendEvent(appender, stored);
            }
            appender.flush();
            this.appendSearchText(storedEvents);
            this.connection.commit();
        } catch (SQLException | RuntimeException e) {
            this.discardAppender(e);
            this.rollback(e);
            throw e;
        }
    }

    private List<StoredEvent> prepareStoredEvents(List<QueuedEvent> events) throws SQLException {
        var storedEvents = new ArrayList<StoredEvent>(events.size());
        for (var queued : events) {
            var eventId = TimeBasedUUID.generate();
            storedEvents.add(new StoredEvent(queued, eventId));

            var observation = queued.playerNameObservation();
            if (observation == null) {
                continue;
            }

            if (!(queued.submission().actor() instanceof PlayerActor player)) {
                throw new IllegalArgumentException("A player-name observation requires a PlayerActor.");
            }

            var previous = this.latestPlayerName(player.uniqueId()).orElse(null);
            var advancesLatest = previous == null
                || isNewerObservation(queued.occurredAtMillis(), eventId, previous);
            if (
                advancesLatest
                    && previous != null
                    && !previous.name().equals(observation.username())
            ) {
                var nameChange = new EventSubmission(
                    PlayerNameDirectory.NAME_CHANGE_EVENT_TYPE,
                    PayloadGeneration.FIRST,
                    Instant.ofEpochMilli(queued.occurredAtMillis()),
                    null,
                    null,
                    null,
                    player,
                    null,
                    PlayerNameChangePayloadCodec.encode(
                        previous.name(),
                        observation.username()
                    )
                );
                storedEvents.add(new StoredEvent(
                    new QueuedEvent(
                        nameChange,
                        queued.occurredAtMillis(),
                        observation.nameChangeExpiresAtMillis(),
                        null,
                        null
                    ),
                    TimeBasedUUID.generate()
                ));
            }

            this.recordPlayerName(
                player.uniqueId(),
                observation.username(),
                queued.occurredAtMillis(),
                eventId
            );
        }
        return storedEvents;
    }

    private void appendEvent(DuckDBAppender appender, StoredEvent stored) throws SQLException {
        var queued = stored.queued();
        var event = queued.submission();
        appender.beginRow()
            .append(stored.eventId())
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

    private void appendSearchText(List<StoredEvent> storedEvents) throws SQLException {
        try (var statement = this.connection.prepareStatement(
            "INSERT INTO event_search_text (event_id, search_text) VALUES (?, ?)"
        )) {
            var hasRows = false;
            for (var stored : storedEvents) {
                var searchText = stored.queued().searchText();
                if (searchText == null) {
                    continue;
                }
                statement.setObject(1, stored.eventId());
                statement.setString(2, searchText);
                statement.addBatch();
                hasRows = true;
            }
            if (hasRows) {
                statement.executeBatch();
            }
        }
    }

    private Optional<PlayerNameState> latestPlayerName(UUID playerId) throws SQLException {
        try (var statement = this.connection.prepareStatement("""
            SELECT name, epoch_ms(last_seen), last_event_id
            FROM player_name_history
            WHERE player_uuid = ?
            ORDER BY last_seen DESC, last_event_id DESC
            LIMIT 1
            """)) {
            statement.setObject(1, playerId);
            try (var rows = statement.executeQuery()) {
                return rows.next()
                    ? Optional.of(new PlayerNameState(
                        rows.getString(1),
                        rows.getLong(2),
                        rows.getObject(3, UUID.class)
                    ))
                    : Optional.empty();
            }
        }
    }

    private static boolean isNewerObservation(
        long observedAtMillis,
        UUID eventId,
        PlayerNameState previous
    ) {
        var timestampComparison = Long.compare(
            observedAtMillis,
            previous.lastSeenMillis()
        );
        return timestampComparison > 0
            || timestampComparison == 0
            && eventId.compareTo(previous.lastEventId()) > 0;
    }

    private void recordPlayerName(
        UUID playerId,
        String username,
        long observedAtMillis,
        UUID eventId
    ) throws SQLException {
        var normalizedName = normalizeName(username);
        try (var updateFirstSeen = this.connection.prepareStatement("""
            UPDATE player_name_history
            SET
                normalized_name = ?,
                first_seen = least(first_seen, epoch_ms(?))
            WHERE player_uuid = ? AND name = ?
            """)) {
            updateFirstSeen.setString(1, normalizedName);
            updateFirstSeen.setLong(2, observedAtMillis);
            updateFirstSeen.setObject(3, playerId);
            updateFirstSeen.setString(4, username);
            if (updateFirstSeen.executeUpdate() != 0) {
                try (var updateLastSeen = this.connection.prepareStatement("""
                    UPDATE player_name_history
                    SET last_seen = epoch_ms(?), last_event_id = ?
                    WHERE
                        player_uuid = ?
                        AND name = ?
                        AND (
                            last_seen < epoch_ms(?)
                            OR (last_seen = epoch_ms(?) AND last_event_id < ?)
                        )
                    """)) {
                    updateLastSeen.setLong(1, observedAtMillis);
                    updateLastSeen.setObject(2, eventId);
                    updateLastSeen.setObject(3, playerId);
                    updateLastSeen.setString(4, username);
                    updateLastSeen.setLong(5, observedAtMillis);
                    updateLastSeen.setLong(6, observedAtMillis);
                    updateLastSeen.setObject(7, eventId);
                    updateLastSeen.executeUpdate();
                }
                return;
            }
        }

        try (var insert = this.connection.prepareStatement("""
            INSERT INTO player_name_history (
                player_uuid,
                name,
                normalized_name,
                first_seen,
                last_seen,
                last_event_id
            )
            VALUES (?, ?, ?, epoch_ms(?), epoch_ms(?), ?)
            """)) {
            insert.setObject(1, playerId);
            insert.setString(2, username);
            insert.setString(3, normalizedName);
            insert.setLong(4, observedAtMillis);
            insert.setLong(5, observedAtMillis);
            insert.setObject(6, eventId);
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<UUID> resolvePlayerName(String name) throws SQLException {
        var normalizedName = normalizeName(name);
        try (var statement = this.connection.prepareStatement("""
            SELECT player_uuid
            FROM player_name_history
            WHERE normalized_name = ?
            ORDER BY last_seen DESC, last_event_id DESC
            LIMIT 1
            """)) {
            statement.setString(1, normalizedName);
            try (var rows = statement.executeQuery()) {
                return rows.next()
                    ? Optional.of(rows.getObject(1, UUID.class))
                    : Optional.empty();
            }
        }
    }

    @Override
    public List<String> offlinePlayerNames() throws SQLException {
        var names = new ArrayList<String>();
        try (var statement = this.connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT name
                 FROM (
                     SELECT
                         name,
                         normalized_name,
                         row_number() OVER (
                             PARTITION BY normalized_name
                             ORDER BY last_seen DESC, last_event_id DESC
                         ) AS recency_rank
                     FROM player_name_history
                 )
                 WHERE recency_rank = 1
                 ORDER BY normalized_name
                 """)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return List.copyOf(names);
    }

    private static String normalizeName(String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }

    @Override
    public List<UUID> findEventIdsContaining(String literal) throws SQLException {
        Objects.requireNonNull(literal, "literal");
        var eventIds = new ArrayList<UUID>();
        try (var statement = this.connection.prepareStatement("""
            SELECT event_id
            FROM event_search_text
            WHERE contains(lower(search_text), lower(?))
            ORDER BY event_id
            """)) {
            statement.setString(1, literal);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    eventIds.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return List.copyOf(eventIds);
    }

    @Override
    public SearchPage search(SearchRequest request) throws SQLException {
        return DuckDbEventSearch.search(this.connection, request);
    }

    @Override
    public SearchMetadata searchMetadata() throws SQLException {
        var eventTypes = new LinkedHashSet<Key>();
        var worlds = new LinkedHashSet<Key>();
        var actorTypes = new LinkedHashSet<Key>();
        var targetTypes = new LinkedHashSet<Key>();

        try (var statement = this.connection.createStatement();
             var rows = statement.executeQuery("""
                 SELECT category, value
                 FROM (
                     SELECT 'event' AS category, event_type AS value FROM events
                     UNION
                     SELECT 'world', world FROM events WHERE world IS NOT NULL
                     UNION
                     SELECT 'actor', actor_type FROM events WHERE actor_type IS NOT NULL
                     UNION
                     SELECT 'target', target_type FROM events WHERE target_type IS NOT NULL
                 )
                 ORDER BY category, value
                 """)) {
            while (rows.next()) {
                var value = Key.key(rows.getString("value"));
                switch (rows.getString("category")) {
                    case "event" -> eventTypes.add(value);
                    case "world" -> worlds.add(value);
                    case "actor" -> actorTypes.add(value);
                    case "target" -> targetTypes.add(value);
                    default -> throw new SQLException("Unexpected search metadata category.");
                }
            }
        }

        return new SearchMetadata(eventTypes, worlds, actorTypes, targetTypes);
    }

    @Override
    public int deleteExpired(Instant now) throws SQLException {
        try (
            var deleteSearchText = this.connection.prepareStatement("""
                DELETE FROM event_search_text
                WHERE event_id IN (
                    SELECT event_id
                    FROM events
                    WHERE expires_at <= epoch_ms(?)
                )
                """);
            var deleteEvents = this.connection.prepareStatement(
                "DELETE FROM events WHERE expires_at <= epoch_ms(?)"
            )
        ) {
            var nowMillis = now.toEpochMilli();
            deleteSearchText.setLong(1, nowMillis);
            deleteSearchText.executeUpdate();

            deleteEvents.setLong(1, nowMillis);
            var deleted = deleteEvents.executeUpdate();
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

    private record PlayerNameState(
        String name,
        long lastSeenMillis,
        UUID lastEventId
    ) {
    }

    private record StoredEvent(QueuedEvent queued, UUID eventId) {
    }
}
