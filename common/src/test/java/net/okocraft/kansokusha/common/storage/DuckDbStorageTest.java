package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
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

class DuckDbStorageTest {

    private static final Key SHORT = Key.key("example", "short");
    private static final Key LONG = Key.key("example", "long");
    private static final KansokushaConfig.Retention RETENTION = new KansokushaConfig.Retention(
        Map.of(LONG, Duration.ofDays(10)),
        Duration.ofDays(1)
    );
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00.123Z");

    @Test
    void testAppendPersistsAllColumns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        var player = UUID.randomUUID();

        try (var storage = DuckDbStorage.open(file)) {
            storage.append(List.of(
                new EventSubmission(
                    LONG, new PayloadGeneration(2), NOW,
                    Key.key("example", "server"), Key.key("minecraft", "overworld"), new BlockPosition(1, -2, 3),
                    new PlayerSubject(player), EventPayload.copyOf(new byte[]{1, 2, 3})
                ),
                new EventSubmission(
                    SHORT, PayloadGeneration.FIRST, NOW, null, null, null, null, EventPayload.copyOf(new byte[0])
                )
            ), RETENTION);
        }

        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery(
                 "SELECT event_type, payload_generation, epoch_ms(occurred_at), server, world, x, y, z, player, "
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
            Assertions.assertEquals(player, rows.getObject(9, UUID.class));
            Assertions.assertEquals(NOW.plus(Duration.ofDays(10)).toEpochMilli(), rows.getLong(10));
            Assertions.assertArrayEquals(new byte[]{1, 2, 3}, rows.getBytes(11));

            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("example:short", rows.getString(1));
            Assertions.assertNull(rows.getString(4));
            Assertions.assertNull(rows.getString(5));
            Assertions.assertNull(rows.getObject(6));
            Assertions.assertNull(rows.getObject(9));
            Assertions.assertEquals(NOW.plus(Duration.ofDays(1)).toEpochMilli(), rows.getLong(10));

            Assertions.assertFalse(rows.next());
        }
    }

    @Test
    void testDeleteExpiredRemovesOnlyExpiredEvents(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorage.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(event(SHORT), event(LONG)), RETENTION);

            Assertions.assertEquals(0, storage.deleteExpired(NOW.plus(Duration.ofDays(1)).minusMillis(1)));
            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(1))));
            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(10))));
        }
    }

    @Test
    void testReopeningKeepsExistingEvents(@TempDir Path dir) throws Exception {
        var file = dir.resolve("kansokusha.duckdb");
        try (var storage = DuckDbStorage.open(file)) {
            storage.append(List.of(event(LONG)), RETENTION);
        }
        try (var storage = DuckDbStorage.open(file)) {
            Assertions.assertEquals(1, storage.deleteExpired(NOW.plus(Duration.ofDays(10))));
        }
    }

    private static EventSubmission event(Key type) {
        return new EventSubmission(type, PayloadGeneration.FIRST, NOW, null, null, null, null, EventPayload.copyOf(new byte[0]));
    }
}
