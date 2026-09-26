package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.storage.duckdb.DuckDbStorageImpl;
import org.duckdb.DuckDBDriver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class DuckDbStorageTest {

    private static final Key SHORT = Key.key("example", "short");
    private static final Key LONG = Key.key("example", "long");
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
    void testOpenRejectsIncompatibleEventsTable(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE events (event_type VARCHAR NOT NULL, player UUID)");
        }

        Assertions.assertThrows(java.sql.SQLException.class, () -> DuckDbStorageImpl.open(file).close());
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
        return new QueuedEvent(
            event,
            event.occurredAt().toEpochMilli(),
            event.occurredAt().plus(DURATIONS.getOrDefault(event.eventType(), Duration.ofDays(1))).toEpochMilli()
        );
    }

    private static EventSubmission event(Key type) {
        return new EventSubmission(
            type, PayloadGeneration.FIRST, NOW, null, null, null, null, null, EventPayload.copyOf(new byte[0])
        );
    }
}
