package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import net.okocraft.kansokusha.common.storage.Storage;
import net.okocraft.kansokusha.common.storage.StorageHealth;
import net.okocraft.kansokusha.common.storage.duckdb.DuckDbStorageImpl;
import org.duckdb.DuckDBDriver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    void testInternalSearchBackendPersistsAndFindsDerivedText(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));

            var now = Instant.now();
            Assertions.assertTrue(runtime.submitSearchable(event(now), "Mixed BAN%_* text"));
            Assertions.assertTrue(runtime.submit(event(now)));

            Assertions.assertEquals(1, runtime.findEventIdsContaining("bAn").join().size());
            Assertions.assertEquals(1, runtime.findEventIdsContaining("%_*").join().size());
            Assertions.assertTrue(runtime.findEventIdsContaining("missing").join().isEmpty());
        }
    }

    @Test
    void testEventLookupRunsOnStorageThreadWithoutBlockingCaller() throws Exception {
        var storage = new BlockingLookupStorage();
        var runtime = KansokushaRuntime.start(
            storage,
            config(10, BATCH_SIZE),
            SERVER_KEY,
            ignored -> {
            },
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );
        var eventId = UUID.fromString("0199a123-4567-789a-8bcd-ef0123456792");

        try {
            var callerThread = Thread.currentThread();
            var result = runtime.findEvent(eventId);

            Assertions.assertTrue(storage.lookupStarted.await(5, TimeUnit.SECONDS));
            Assertions.assertFalse(result.isDone());
            Assertions.assertNotSame(callerThread, storage.lookupThread.get());
            Assertions.assertEquals("kansokusha-storage", storage.lookupThread.get().getName());
            Assertions.assertEquals(eventId, storage.lookupEventId.get());

            storage.lookupRelease.countDown();
            Assertions.assertTrue(result.get(5, TimeUnit.SECONDS).isEmpty());
        } finally {
            storage.lookupRelease.countDown();
            runtime.close();
        }
    }

    @Test
    void testTypedSearchRunsOnStorageThreadWithoutBlockingCaller() throws Exception {
        var storage = new BlockingLookupStorage();
        var runtime = KansokushaRuntime.start(
            storage,
            config(10, BATCH_SIZE),
            SERVER_KEY,
            ignored -> {
            },
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );
        var request = new SearchRequest(
            SearchQueryParser.parse("", Clock.systemUTC(), ZoneOffset.UTC),
            new SearchRequest.Constraints(Set.of(EVENT_TYPE)),
            Optional.empty(),
            Optional.empty(),
            10
        );

        try {
            var callerThread = Thread.currentThread();
            var result = runtime.search(request);

            Assertions.assertTrue(storage.searchStarted.await(5, TimeUnit.SECONDS));
            Assertions.assertFalse(result.isDone());
            Assertions.assertNotSame(callerThread, storage.searchThread.get());
            Assertions.assertEquals("kansokusha-storage", storage.searchThread.get().getName());
            Assertions.assertSame(request, storage.searchRequest.get());

            storage.searchRelease.countDown();
            Assertions.assertTrue(result.get(5, TimeUnit.SECONDS).events().isEmpty());
        } finally {
            storage.searchRelease.countDown();
            runtime.close();
        }
    }

    @Test
    void testTypedSearchDoesNotForceQueuedWritesToFlush(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            var request = new SearchRequest(
                SearchQueryParser.parse("", Clock.systemUTC(), ZoneOffset.UTC),
                new SearchRequest.Constraints(Set.of(EVENT_TYPE)),
                Optional.empty(),
                Optional.empty(),
                10
            );

            Assertions.assertTrue(runtime.submit(event(Instant.now())));
            Assertions.assertTrue(runtime.search(request).join().events().isEmpty());
            Assertions.assertEquals(0, countEvents(dir));

            Assertions.assertTrue(runtime.submit(event(Instant.now())));
            Assertions.assertEquals(BATCH_SIZE, runtime.search(request).join().events().size());
        }
    }

    @Test
    void testExpiredEventsAreDeletedOnClose(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            runtime.submit(event(Instant.now().minus(Duration.ofDays(2))));
            runtime.submit(event(Instant.now()));
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
    void testCloseFlushesRemainingEventsOnStorageThread(@TempDir Path dir) throws Exception {
        var runtime = start(dir, 10);
        runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));

        runtime.submit(event(Instant.now()));
        runtime.submit(event(Instant.now()));

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (countEvents(dir) != BATCH_SIZE && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertEquals(BATCH_SIZE, countEvents(dir));

        Assertions.assertTrue(runtime.submit(event(Instant.now())));
        runtime.close();

        Assertions.assertEquals(BATCH_SIZE + 1, countEvents(dir));
    }

    @Test
    void testCloseReportsDatabaseHealth(@TempDir Path dir) throws Exception {
        var report = new AtomicReference<String>();
        var runtime = start(dir, 10, BATCH_SIZE, report::set);
        runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
        Assertions.assertTrue(runtime.submit(event(Instant.now())));

        runtime.close();

        var message = report.get();
        Assertions.assertNotNull(message);
        Assertions.assertTrue(message.contains("events=1"));
        Assertions.assertTrue(message.contains("reusable="));
        Assertions.assertTrue(message.contains("expired-on-shutdown="));
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

    private static final class BlockingLookupStorage implements Storage {

        private final CountDownLatch lookupStarted = new CountDownLatch(1);
        private final CountDownLatch lookupRelease = new CountDownLatch(1);
        private final AtomicReference<Thread> lookupThread = new AtomicReference<>();
        private final AtomicReference<UUID> lookupEventId = new AtomicReference<>();
        private final CountDownLatch searchStarted = new CountDownLatch(1);
        private final CountDownLatch searchRelease = new CountDownLatch(1);
        private final AtomicReference<Thread> searchThread = new AtomicReference<>();
        private final AtomicReference<SearchRequest> searchRequest = new AtomicReference<>();

        @Override
        public void append(List<QueuedEvent> events) {
        }

        @Override
        public Optional<UUID> resolvePlayerName(String name) {
            return Optional.empty();
        }

        @Override
        public List<String> offlinePlayerNames() {
            return List.of();
        }

        @Override
        public List<UUID> findEventIdsContaining(String literal) {
            return List.of();
        }

        @Override
        public Optional<EventDetail> findEvent(UUID eventId) throws SQLException {
            this.lookupThread.set(Thread.currentThread());
            this.lookupEventId.set(eventId);
            this.lookupStarted.countDown();
            try {
                this.lookupRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting in test storage.", e);
            }
            return Optional.empty();
        }

        @Override
        public SearchPage search(SearchRequest request) throws SQLException {
            this.searchThread.set(Thread.currentThread());
            this.searchRequest.set(request);
            this.searchStarted.countDown();
            try {
                this.searchRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting in test storage.", e);
            }
            return new SearchPage(List.of(), Optional.empty(), Optional.empty());
        }

        @Override
        public int deleteExpired(Instant now) {
            return 0;
        }

        @Override
        public void checkpoint() {
        }

        @Override
        public StorageHealth health() {
            return new StorageHealth(0, "0 B", 0, 0, 0, 0, "0 B");
        }

        @Override
        public void close() {
        }
    }

    private static KansokushaRuntime start(Path dir, int queueCapacity) throws Exception {
        return start(dir, queueCapacity, BATCH_SIZE);
    }

    private static KansokushaRuntime start(Path dir, int queueCapacity, int batchSize) throws Exception {
        return start(dir, queueCapacity, batchSize, message -> {
        });
    }

    private static KansokushaRuntime start(
        Path dir,
        int queueCapacity,
        int batchSize,
        Consumer<String> infoReporter
    ) throws Exception {
        var storage = DuckDbStorageImpl.open(dir.resolve(KansokushaRuntime.DATABASE_FILENAME));
        return KansokushaRuntime.start(
            storage,
            config(queueCapacity, batchSize),
            SERVER_KEY,
            infoReporter,
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );
    }

    private static KansokushaConfig config(int queueCapacity, int batchSize) {
        return new KansokushaConfig(
            Optional.empty(),
            queueCapacity,
            batchSize,
            Duration.ofHours(1),
            Duration.ofHours(1),
            java.time.ZoneOffset.UTC,
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
