package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.storage.duckdb.DuckDbStorageImpl;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

class KansokushaRuntimeTest {

    private static final Key EVENT_TYPE = Key.key("example", "event");
    private static final Key SERVER_KEY = Key.key("example", "server");
    private static final int BATCH_SIZE = 2;

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
        var runtime = start(dir, 2, 10);
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
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (countEvents(dir) != 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }
        Assertions.assertEquals(1, countEvents(dir));
    }

    @Test
    void testInvalidEventIsRejectedWithoutAffectingOthers(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            runtime.submit(event(Instant.now()));

            Assertions.assertThrows(IllegalArgumentException.class, () -> runtime.submit(event(Instant.MAX)));
            Assertions.assertThrows(IllegalArgumentException.class, () -> runtime.submit(event(Instant.MIN)));
            // occurredAt fits in epoch milliseconds, but expiresAt does not.
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> runtime.submit(event(Instant.ofEpochMilli(Long.MAX_VALUE - 1)))
            );
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> runtime.submit(event(Instant.ofEpochMilli(Long.MIN_VALUE)))
            );

            runtime.submit(event(Instant.now()));
        }
        Assertions.assertEquals(2, countEvents(dir));
    }

    @Test
    void testFullBatchIsWrittenBeforeFlushInterval(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            runtime.submit(event(Instant.now()));
            Thread.sleep(200);
            Assertions.assertEquals(0, countEvents(dir), "A partial batch waits for the flush interval.");

            runtime.submit(event(Instant.now()));
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (countEvents(dir) != BATCH_SIZE && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Assertions.assertEquals(BATCH_SIZE, countEvents(dir));
        }
    }

    @Test
    void testConcurrentFullBatchIsWrittenBeforeFlushInterval(@TempDir Path dir) throws Exception {
        var batchSize = 32;
        try (
            var runtime = start(dir, 64, batchSize);
            var submitters = Executors.newFixedThreadPool(4)
        ) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var thread = 0; thread < 4; thread++) {
                futures.add(submitters.submit(() -> {
                    start.await();
                    for (var index = 0; index < batchSize / 4; index++) {
                        Assertions.assertTrue(runtime.submit(event(Instant.now())));
                    }
                    return null;
                }));
            }

            start.countDown();
            for (var future : futures) {
                future.get();
            }

            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (countEvents(dir) != batchSize && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Assertions.assertEquals(batchSize, countEvents(dir));
        }
    }

    @Test
    void testClosePersistsAllAcceptedConcurrentSubmissions(@TempDir Path dir) throws Exception {
        var runtime = start(dir, 100_000, 100_001);
        runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
        var accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        var now = Instant.now();

        try (var submitters = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var thread = 0; thread < 8; thread++) {
                futures.add(submitters.submit(() -> {
                    start.await();
                    while (runtime.submit(event(now))) {
                        accepted.incrementAndGet();
                    }
                    return null;
                }));
            }

            start.countDown();
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (accepted.get() < 100 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            Assertions.assertTrue(accepted.get() >= 100, "Concurrent submissions did not start.");

            runtime.close();
            for (var future : futures) {
                future.get();
            }
        }

        Assertions.assertEquals(accepted.get(), countEvents(dir));
        Assertions.assertFalse(runtime.submit(event(now)));
    }

    private static KansokushaRuntime start(Path dir, int queueCapacity) throws Exception {
        return start(dir, queueCapacity, BATCH_SIZE);
    }

    private static KansokushaRuntime start(Path dir, int queueCapacity, int batchSize) throws Exception {
        var storage = DuckDbStorageImpl.open(dir.resolve(KansokushaRuntime.DATABASE_FILENAME));
        return KansokushaRuntime.start(storage, config(queueCapacity, batchSize), SERVER_KEY, (message, failure) -> {
            throw new AssertionError(message, failure);
        });
    }

    private static KansokushaConfig config(int queueCapacity, int batchSize) {
        return new KansokushaConfig(
            Optional.empty(),
            queueCapacity,
            batchSize,
            Duration.ofHours(1),
            Duration.ofHours(1),
            new KansokushaConfig.Retention(Map.of(), Duration.ofDays(1))
        );
    }

    private static EventSubmission event(Instant occurredAt) {
        return new EventSubmission(
            EVENT_TYPE, PayloadGeneration.FIRST, occurredAt, SERVER_KEY, null, null, null, null,
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
