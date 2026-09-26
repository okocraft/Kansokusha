package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.duckdb.DuckDBDriver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

class KansokushaRuntimeTest {

    private static final Key EVENT_TYPE = Key.key("example", "event");
    private static final Key SERVER_KEY = Key.key("example", "server");

    @Test
    void testRegistrationIsIdempotentAndRejectsConflicts(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));

            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, new PayloadGeneration(2)))
            );
        }
    }

    @Test
    void testSubmitRejectsUnregisteredTypesAndGenerations(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> runtime.submit(event(Instant.now())));

            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, new PayloadGeneration(2)));
            Assertions.assertThrows(IllegalArgumentException.class, () -> runtime.submit(event(Instant.now())));
        }
    }

    @Test
    void testQueuedEventsArePersistedOnClose(@TempDir Path dir) throws Exception {
        var runtime = start(dir, 2);
        Assertions.assertEquals(Optional.of(SERVER_KEY), runtime.localServerKey());
        runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));

        var now = Instant.now();
        Assertions.assertTrue(runtime.submit(event(now)));
        Assertions.assertTrue(runtime.submit(event(now)));
        Assertions.assertFalse(runtime.submit(event(now)), "The queue is full.");

        runtime.close();
        Assertions.assertFalse(runtime.submit(event(now)), "The runtime is closed.");
        runtime.close();

        Assertions.assertEquals(2, countEvents(dir));
    }

    @Test
    void testExpiredEventsAreDeletedOnStart(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            runtime.submit(event(Instant.now().minus(Duration.ofDays(2))));
            runtime.submit(event(Instant.now()));
        }
        Assertions.assertEquals(2, countEvents(dir));

        try (var ignored = start(dir, 10)) {
            // The first cleanup runs asynchronously right after start.
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (countEvents(dir) != 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }
        Assertions.assertEquals(1, countEvents(dir));
    }

    @Test
    void testWriteFailuresAreReported(@TempDir Path dir) throws Exception {
        var errors = new ArrayList<String>();
        var runtime = KansokushaRuntime.start(dir, config(10), null, (message, failure) -> errors.add(message));
        runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
        // Instant.MAX cannot be converted to epoch milliseconds, so writing the batch fails.
        runtime.submit(event(Instant.MAX));
        runtime.close();

        Assertions.assertEquals(1, errors.size());
        Assertions.assertTrue(errors.getFirst().contains("1 events"), errors.getFirst());
    }

    private static KansokushaRuntime start(Path dir, int queueCapacity) throws Exception {
        return KansokushaRuntime.start(dir, config(queueCapacity), SERVER_KEY, (message, failure) -> {
            throw new AssertionError(message, failure);
        });
    }

    private static KansokushaConfig config(int queueCapacity) {
        return new KansokushaConfig(
            Optional.empty(),
            queueCapacity,
            Duration.ofHours(1),
            Duration.ofHours(1),
            new KansokushaConfig.Retention(Map.of(), Duration.ofDays(1))
        );
    }

    private static EventSubmission event(Instant occurredAt) {
        return new EventSubmission(
            EVENT_TYPE, PayloadGeneration.FIRST, occurredAt, SERVER_KEY, null, null, null,
            EventPayload.copyOf(new byte[]{1})
        );
    }

    private static long countEvents(Path dir) throws SQLException {
        var file = dir.resolve(KansokushaRuntime.DATABASE_FILENAME);
        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery("SELECT count(*) FROM events")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
