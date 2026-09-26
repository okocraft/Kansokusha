package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.player.PlayerNameChangePayloadCodec;
import net.okocraft.kansokusha.common.player.PlayerNameDirectory;
import net.okocraft.kansokusha.common.storage.duckdb.DuckDbStorageImpl;
import org.duckdb.DuckDBDriver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class DuckDbStorageTest {

    private static final Key SHORT = Key.key("example", "short");
    private static final Key LONG = Key.key("example", "long");
    private static final Key LOGIN = Key.key("kansokusha", "paper_join");
    private static final Map<Key, Duration> DURATIONS = Map.of(LONG, Duration.ofDays(10));
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00.123Z");

    @Test
    void testAppendPersistsAllColumns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var player = UUID.randomUUID();

        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(Stream.of(
                new EventSubmission(
                    LONG, new PayloadGeneration(2), NOW,
                    Key.key("example", "server"), Key.key("minecraft", "overworld"), new BlockPosition(1, -2, 3),
                    new PlayerActor(player), Key.key("minecraft", "stone"), EventPayload.copyOf(new byte[]{1, 2, 3})
                ),
                new EventSubmission(
                    SHORT, PayloadGeneration.FIRST, NOW, null, null, null, null, null, EventPayload.copyOf(new byte[0])
                )
            ).map(DuckDbStorageTest::queued).toList());
        }

        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery(
                 "SELECT event_type, payload_generation, epoch_ms(occurred_at), server, world, x, y, z, "
                     + "actor_kind, actor_uuid, actor_type, target_type, "
                     + "epoch_ms(expires_at), payload FROM events ORDER BY event_type"
             )) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("example:long", rows.getString(1));
            Assertions.assertEquals(2, rows.getInt(2));
            Assertions.assertEquals(NOW.toEpochMilli(), rows.getLong(3));
            Assertions.assertEquals("example:server", rows.getString(4));
            Assertions.assertEquals("minecraft:overworld", rows.getString(5));
            Assertions.assertEquals(1, rows.getInt(6));
            Assertions.assertEquals(-2, rows.getInt(7));
            Assertions.assertEquals(3, rows.getInt(8));
            Assertions.assertEquals("player", rows.getString(9));
            Assertions.assertEquals(player, rows.getObject(10, UUID.class));
            Assertions.assertNull(rows.getString(11));
            Assertions.assertEquals("minecraft:stone", rows.getString(12));
            Assertions.assertEquals(NOW.plus(Duration.ofDays(10)).toEpochMilli(), rows.getLong(13));
            Assertions.assertArrayEquals(new byte[]{1, 2, 3}, rows.getBytes(14));

            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("example:short", rows.getString(1));
            Assertions.assertNull(rows.getString(4));
            Assertions.assertNull(rows.getString(5));
            Assertions.assertNull(rows.getObject(6));
            Assertions.assertNull(rows.getString(9));
            Assertions.assertNull(rows.getObject(10));
            Assertions.assertNull(rows.getString(11));
            Assertions.assertNull(rows.getString(12));
            Assertions.assertEquals(NOW.plus(Duration.ofDays(1)).toEpochMilli(), rows.getLong(13));

            Assertions.assertFalse(rows.next());
        }
    }

    @Test
    void testAppendPersistsEntityAndBlockActors(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var entity = UUID.randomUUID();

        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(Stream.of(
                new EventSubmission(
                    LONG, PayloadGeneration.FIRST, NOW, null, null, null,
                    new EntityActor(entity, Key.key("minecraft", "creeper")), Key.key("minecraft", "dirt"),
                    EventPayload.copyOf(new byte[0])
                ),
                new EventSubmission(
                    SHORT, PayloadGeneration.FIRST, NOW, null, null, null,
                    new BlockActor(Key.key("minecraft", "piston")), Key.key("minecraft", "sand"),
                    EventPayload.copyOf(new byte[0])
                )
            ).map(DuckDbStorageTest::queued).toList());
        }

        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery(
                 "SELECT actor_kind, actor_uuid, actor_type, target_type FROM events ORDER BY event_type"
             )) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("entity", rows.getString(1));
            Assertions.assertEquals(entity, rows.getObject(2, UUID.class));
            Assertions.assertEquals("minecraft:creeper", rows.getString(3));
            Assertions.assertEquals("minecraft:dirt", rows.getString(4));

            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("block", rows.getString(1));
            Assertions.assertNull(rows.getObject(2));
            Assertions.assertEquals("minecraft:piston", rows.getString(3));
            Assertions.assertEquals("minecraft:sand", rows.getString(4));

            Assertions.assertFalse(rows.next());
        }
    }

    @Test
    void testAppendAssignsUniqueUuidV7IdsAndStableOrderForSameOccurredAt(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var eventCount = 128;

        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(IntStream.range(0, eventCount)
                .mapToObj(index -> queued(event(SHORT)))
                .toList());
        }

        var eventIds = new ArrayList<UUID>(eventCount);
        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery(
                 "SELECT event_id, epoch_ms(occurred_at) FROM events ORDER BY occurred_at, event_id"
             )) {
            while (rows.next()) {
                var eventId = rows.getObject(1, UUID.class);
                Assertions.assertNotNull(eventId);
                Assertions.assertEquals(7, eventId.version());
                Assertions.assertEquals(NOW.toEpochMilli(), rows.getLong(2));
                eventIds.add(eventId);
            }
        }

        Assertions.assertEquals(eventCount, eventIds.size());
        Assertions.assertEquals(eventCount, new HashSet<>(eventIds).size());
        for (var index = 1; index < eventIds.size(); index++) {
            Assertions.assertTrue(
                eventIds.get(index - 1).compareTo(eventIds.get(index)) < 0,
                "event_id must provide a stable tie-break for equal occurred_at values"
            );
        }
    }

    @Test
    void testOpenRejectsEventsTableWithoutEventId(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE events (
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
                """);
        }

        Assertions.assertThrows(java.sql.SQLException.class, () -> DuckDbStorageImpl.open(file).close());
    }

    @Test
    void testSearchTextUsesCaseInsensitiveLiteralSubstringAndSkipsUnrelatedEvents(
        @TempDir Path dir
    ) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var unreadablePayload = new EventSubmission(
            SHORT,
            PayloadGeneration.FIRST,
            NOW,
            null,
            null,
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{(byte) 0xff, 0x00, 0x7f})
        );

        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(List.of(
                queued(unreadablePayload, "Prefix Ban%_* Suffix"),
                queued(event(LONG), "another BAN entry"),
                queued(new EventSubmission(
                    Key.key("example", "unrelated"),
                    PayloadGeneration.FIRST,
                    NOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    EventPayload.copyOf(new byte[]{1, 2, 3})
                ))
            ));

            Assertions.assertEquals(2, storage.findEventIdsContaining("bAn").size());
            Assertions.assertEquals(1, storage.findEventIdsContaining("%").size());
            Assertions.assertEquals(1, storage.findEventIdsContaining("_").size());
            Assertions.assertEquals(1, storage.findEventIdsContaining("*").size());
            Assertions.assertTrue(storage.findEventIdsContaining("ban?").isEmpty());
        }

        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery("""
                 SELECT
                     (SELECT count(*) FROM events),
                     (SELECT count(*) FROM event_search_text)
                 """)) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals(3, rows.getLong(1));
            Assertions.assertEquals(2, rows.getLong(2));
        }
    }

    @Test
    void testDeleteExpiredRemovesMatchingSearchTextProjection(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(
                queued(event(SHORT), "short-only"),
                queued(event(LONG), "long-only")
            ));

            Assertions.assertEquals(1, storage.findEventIdsContaining("short-only").size());
            Assertions.assertEquals(1, storage.findEventIdsContaining("long-only").size());

            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(1))));
            Assertions.assertTrue(storage.findEventIdsContaining("short-only").isEmpty());
            Assertions.assertEquals(1, storage.findEventIdsContaining("long-only").size());

            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(10))));
            Assertions.assertTrue(storage.findEventIdsContaining("long-only").isEmpty());
        }
    }

    @Test
    void testPlayerNameObservationCreatesOnlyRealNameChanges(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var player = UUID.fromString("123e4567-e89b-12d3-a456-426614174100");
        var renamedAt = NOW.plusSeconds(2);

        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(List.of(queuedLogin(player, "FirstName", NOW)));
            storage.append(List.of(queuedLogin(player, "FirstName", NOW.plusSeconds(1))));
            storage.append(List.of(queuedLogin(player, "SecondName", renamedAt)));
        }

        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var statement = connection.prepareStatement("""
                 SELECT event_type, epoch_ms(occurred_at), actor_kind, actor_uuid, payload
                 FROM events
                 WHERE event_type = ?
                 ORDER BY occurred_at, event_id
                 """)) {
            statement.setString(1, PlayerNameDirectory.NAME_CHANGE_EVENT_TYPE.asString());
            try (var rows = statement.executeQuery()) {
                Assertions.assertTrue(rows.next());
                Assertions.assertEquals(
                    PlayerNameDirectory.NAME_CHANGE_EVENT_TYPE.asString(),
                    rows.getString("event_type")
                );
                Assertions.assertEquals(renamedAt.toEpochMilli(), rows.getLong(2));
                Assertions.assertEquals("player", rows.getString(3));
                Assertions.assertEquals(player, rows.getObject(4, UUID.class));
                var payload = PlayerNameChangePayloadCodec.decode(
                    EventPayload.copyOf(rows.getBytes(5))
                );
                Assertions.assertEquals("FirstName", payload.previousName());
                Assertions.assertEquals("SecondName", payload.newName());
                Assertions.assertFalse(rows.next());
            }
        }
    }

    @Test
    void testStalePlayerNameObservationDoesNotRewindOrCreateReverseChange(
        @TempDir Path dir
    ) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var player = UUID.fromString("123e4567-e89b-12d3-a456-426614174105");
        var currentAt = NOW.plusSeconds(2);
        var renamedAt = NOW.plusSeconds(3);

        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(List.of(queuedLogin(player, "CurrentName", currentAt)));
            storage.append(List.of(queuedLogin(player, "CurrentName", NOW.plusSeconds(1))));
            storage.append(List.of(queuedLogin(player, "StaleName", NOW)));
            storage.append(List.of(queuedLogin(player, "RenamedName", renamedAt)));
        }

        try (var connection = new DuckDBDriver().connect(
            "jdbc:duckdb:" + file,
            new Properties()
        )) {
            try (var statement = connection.prepareStatement("""
                SELECT name, epoch_ms(first_seen), epoch_ms(last_seen)
                FROM player_name_history
                WHERE player_uuid = ?
                ORDER BY last_seen DESC, last_event_id DESC
                """)) {
                statement.setObject(1, player);
                try (var rows = statement.executeQuery()) {
                    Assertions.assertTrue(rows.next());
                    Assertions.assertEquals("RenamedName", rows.getString(1));
                    Assertions.assertEquals(renamedAt.toEpochMilli(), rows.getLong(3));

                    Assertions.assertTrue(rows.next());
                    Assertions.assertEquals("CurrentName", rows.getString(1));
                    Assertions.assertEquals(
                        NOW.plusSeconds(1).toEpochMilli(),
                        rows.getLong(2)
                    );
                    Assertions.assertEquals(currentAt.toEpochMilli(), rows.getLong(3));

                    Assertions.assertTrue(rows.next());
                    Assertions.assertEquals("StaleName", rows.getString(1));
                    Assertions.assertEquals(NOW.toEpochMilli(), rows.getLong(3));
                    Assertions.assertFalse(rows.next());
                }
            }

            try (var statement = connection.prepareStatement("""
                SELECT epoch_ms(occurred_at), payload
                FROM events
                WHERE event_type = ?
                ORDER BY occurred_at, event_id
                """)) {
                statement.setString(
                    1,
                    PlayerNameDirectory.NAME_CHANGE_EVENT_TYPE.asString()
                );
                try (var rows = statement.executeQuery()) {
                    Assertions.assertTrue(rows.next());
                    Assertions.assertEquals(renamedAt.toEpochMilli(), rows.getLong(1));
                    var payload = PlayerNameChangePayloadCodec.decode(
                        EventPayload.copyOf(rows.getBytes(2))
                    );
                    Assertions.assertEquals("CurrentName", payload.previousName());
                    Assertions.assertEquals("RenamedName", payload.newName());
                    Assertions.assertFalse(rows.next());
                }
            }
        }
    }

    @Test
    void testResolvePlayerNameUsesNewestHistoricalOwnerCaseInsensitively(
        @TempDir Path dir
    ) throws Exception {
        var first = UUID.fromString("123e4567-e89b-12d3-a456-426614174101");
        var second = UUID.fromString("123e4567-e89b-12d3-a456-426614174102");

        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(queuedLogin(first, "SharedName", NOW)));
            storage.append(List.of(queuedLogin(second, "SharedName", NOW.plusSeconds(1))));

            Assertions.assertEquals(
                Optional.of(second),
                storage.resolvePlayerName("sHaReDnAmE")
            );

            storage.append(List.of(queuedLogin(first, "SharedName", NOW.plusSeconds(2))));
            Assertions.assertEquals(
                Optional.of(first),
                storage.resolvePlayerName("SHAREDNAME")
            );
        }
    }

    @Test
    void testOfflinePlayerNamesAreCaseInsensitiveCandidates(@TempDir Path dir)
        throws Exception {
        var first = UUID.fromString("123e4567-e89b-12d3-a456-426614174103");
        var second = UUID.fromString("123e4567-e89b-12d3-a456-426614174104");

        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(queuedLogin(first, "FirstName", NOW)));
            storage.append(List.of(queuedLogin(first, "FIRSTNAME", NOW.plusSeconds(1))));
            storage.append(List.of(queuedLogin(second, "OtherName", NOW.plusSeconds(2))));

            Assertions.assertEquals(
                List.of("FIRSTNAME", "OtherName"),
                storage.offlinePlayerNames()
            );
        }
    }

    @Test
    void testDeleteExpiredRemovesOnlyExpiredEvents(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(queued(event(SHORT)), queued(event(LONG))));

            Assertions.assertEquals(0, storage.deleteExpired(NOW.plus(Duration.ofDays(1)).minusMillis(1)));
            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(1))));
            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(10))));
        }
    }

    @Test
    void testCheckpointAndHealthReportStorageState(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(queued(event(SHORT)), queued(event(LONG))));
            storage.checkpoint();

            var health = storage.health();
            Assertions.assertEquals(2, health.eventCount());
            Assertions.assertFalse(health.databaseSize().isBlank());
            Assertions.assertTrue(health.blockSize() > 0);
            Assertions.assertTrue(health.totalBlocks() >= health.usedBlocks());
            Assertions.assertTrue(health.freeBlocks() >= 0);
            Assertions.assertFalse(health.walSize().isBlank());
        }
    }

    @Test
    void testAppenderCanBeReusedAcrossTransactions(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(List.of(queued(event(SHORT))));
            Assertions.assertEquals(0, storage.deleteExpired(NOW.minusMillis(1)));
            storage.append(List.of(queued(event(LONG))));
        }

        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery("SELECT count(*) FROM events")) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals(2, rows.getLong(1));
        }
    }

    @Test
    void testManyDistinctKeysArePersisted(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var worlds = 3000;
        try (var storage = DuckDbStorageImpl.open(file)) {
            for (int round = 0; round < 2; round++) {
                storage.append(IntStream.range(0, worlds).mapToObj(i -> queued(new EventSubmission(
                    SHORT, PayloadGeneration.FIRST, NOW, Key.key("example", "server"), Key.key("w", "world_" + i),
                    new BlockPosition(i, 0, 0), null, null, EventPayload.copyOf(new byte[0])
                ))).toList());
            }
        }

        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery(
                 "SELECT count(*), count(DISTINCT world), count(*) FILTER (WHERE world = 'w:world_' || x) FROM events"
             )) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals(worlds * 2, rows.getLong(1));
            Assertions.assertEquals(worlds, rows.getLong(2));
            Assertions.assertEquals(worlds * 2, rows.getLong(3));
        }
    }

    @Test
    void testReopeningKeepsExistingEvents(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        try (var storage = DuckDbStorageImpl.open(file)) {
            storage.append(List.of(queued(event(LONG))));
        }
        try (var storage = DuckDbStorageImpl.open(file)) {
            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(10))));
        }
    }

    private static QueuedEvent queued(EventSubmission event) {
        return queued(event, null);
    }

    private static QueuedEvent queued(EventSubmission event, String searchText) {
        return new QueuedEvent(
            event,
            event.occurredAt().toEpochMilli(),
            event.occurredAt().plus(DURATIONS.getOrDefault(event.eventType(), Duration.ofDays(1))).toEpochMilli(),
            null,
            searchText
        );
    }

    private static QueuedEvent queuedLogin(UUID playerId, String username, Instant occurredAt) {
        var event = new EventSubmission(
            LOGIN,
            PayloadGeneration.FIRST,
            occurredAt,
            null,
            null,
            null,
            new PlayerActor(playerId),
            null,
            EventPayload.copyOf(new byte[0])
        );
        return new QueuedEvent(
            event,
            occurredAt.toEpochMilli(),
            occurredAt.plus(Duration.ofDays(30)).toEpochMilli(),
            new PlayerNameObservation(
                username,
                occurredAt.plus(Duration.ofDays(180)).toEpochMilli()
            ),
            null
        );
    }

    private static EventSubmission event(Key type) {
        return new EventSubmission(
            type, PayloadGeneration.FIRST, NOW, null, null, null, null, null, EventPayload.copyOf(new byte[0])
        );
    }
}
