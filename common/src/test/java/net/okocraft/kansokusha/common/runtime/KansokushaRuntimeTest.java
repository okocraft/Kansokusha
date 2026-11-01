package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import net.okocraft.kansokusha.common.storage.Storage;
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
    void testHealthReportsQueueCounters(@TempDir Path dir) throws Exception {
        try (var runtime = start(dir, 2, 10)) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));

            var now = Instant.now();
            Assertions.assertTrue(runtime.submit(event(now)));
            Assertions.assertTrue(runtime.submit(event(now)));
            Assertions.assertFalse(runtime.submit(event(now)), "The queue is full.");

            var health = runtime.health().get(5, TimeUnit.SECONDS);
            Assertions.assertEquals(new net.okocraft.kansokusha.common.search.DatabaseHealth.Queue(2, 2, 0, 1), health.queue());
            Assertions.assertEquals(0, health.stored().events());
            Assertions.assertTrue(health.stored().databaseBytes() > 0);
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
        }

        var file = dir.resolve(KansokushaRuntime.DATABASE_FILENAME);
        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:" + file, new Properties());
             var rows = connection.createStatement().executeQuery(
                 "SELECT search_text FROM events WHERE search_text IS NOT NULL"
             )) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("Mixed BAN%_* text", rows.getString(1));
            Assertions.assertFalse(rows.next());
        }
    }

    @Test
    void testArchiveFileIsPartitionedByUtcMonth() {
        Assertions.assertEquals(
            Path.of("archive", "2026", "01", "events-20260131T235959007Z.parquet"),
            KansokushaRuntime.archiveFile(Path.of("archive"), Instant.parse("2026-01-31T23:59:59.007Z"))
        );
    }

    @Test
    void testExpiredArchivedEventsAreArchivedAtStartupAndOthersAreDeleted(@TempDir Path dir) throws Exception {
        var archived = Key.key("example", "archived");
        var storage = DuckDbStorageImpl.open(dir.resolve(KansokushaRuntime.DATABASE_FILENAME));
        var occurredAt = Instant.now().minus(Duration.ofDays(2));
        storage.append(List.of(
            expired(new EventSubmission(
                archived, PayloadGeneration.FIRST, occurredAt, null, null, null, null, null, EventPayload.copyOf(new byte[]{7})
            )),
            expired(event(occurredAt))
        ));
        var config = config(10, BATCH_SIZE);
        config = new KansokushaConfig(
            config.serverKey(), config.queueCapacity(), config.batchSize(), config.flushInterval(),
            config.cleanupInterval(), config.archiveInterval(), config.searchTimeZone(),
            new KansokushaConfig.Retention(Map.of(), Duration.ofDays(1), Set.of(archived))
        );

        var runtime = KansokushaRuntime.start(storage, dir.resolve("archive"), config, SERVER_KEY, (message, failure) -> {
            throw new AssertionError(message, failure);
        });
        List<Path> files = List.of();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (files.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
                files = parquetFiles(dir.resolve("archive"));
            }
        } finally {
            runtime.close();
        }
        Assertions.assertEquals(1, files.size());
        try (var connection = new DuckDBDriver().connect("jdbc:duckdb:", new Properties());
             var rows = connection.createStatement().executeQuery(
                 "SELECT event_type, payload FROM read_parquet('" + files.getFirst() + "')"
             )) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals("example:archived", rows.getString(1));
            Assertions.assertArrayEquals(new byte[]{7}, rows.getBytes(2));
            Assertions.assertFalse(rows.next());
        }
        try (var connection = new DuckDBDriver().connect(
                 "jdbc:duckdb:" + dir.resolve(KansokushaRuntime.DATABASE_FILENAME), new Properties());
             var rows = connection.createStatement().executeQuery("SELECT count(*) FROM events")) {
            Assertions.assertTrue(rows.next());
            Assertions.assertEquals(0, rows.getLong(1));
        }
    }

    private static List<Path> parquetFiles(Path directory) throws java.io.IOException {
        if (java.nio.file.Files.notExists(directory)) {
            return List.of();
        }
        try (var walk = java.nio.file.Files.walk(directory)) {
            return walk.filter(path -> path.toString().endsWith(".parquet")).toList();
        }
    }

    private static QueuedEvent expired(EventSubmission event) {
        var millis = event.occurredAt().toEpochMilli();
        return new QueuedEvent(event, millis, millis + 1, null, null);
    }

    @Test
    void testChangedRetentionIsAppliedToStoredEventsBeforeTheFirstCleanup(@TempDir Path dir) throws Exception {
        var file = dir.resolve(KansokushaRuntime.DATABASE_FILENAME);
        var occurredAt = Instant.now().minus(Duration.ofDays(2));
        try (var storage = DuckDbStorageImpl.open(file)) {
            // Recorded under a 1-day retention: expired by the old expiry, alive under the 30 days below.
            storage.append(List.of(new QueuedEvent(
                event(occurredAt), occurredAt.toEpochMilli(), occurredAt.plus(Duration.ofDays(1)).toEpochMilli(), null, null
            )));
            storage.recordAppliedRetention(KansokushaRuntime.retentionFingerprint(
                new KansokushaConfig.Retention(Map.of(), Duration.ofDays(1))
            ));
        }
        var base = config(10, BATCH_SIZE);
        var config = new KansokushaConfig(
            base.serverKey(), base.queueCapacity(), base.batchSize(), base.flushInterval(), base.cleanupInterval(),
            base.archiveInterval(), base.searchTimeZone(),
            new KansokushaConfig.Retention(Map.of(), Duration.ofDays(30))
        );

        var messages = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var runtime = KansokushaRuntime.start(
            DuckDbStorageImpl.open(file), Path.of("unused-archive"), config, SERVER_KEY, messages::add,
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );
        runtime.maintenanceStarted().get(5, TimeUnit.SECONDS);
        runtime.close();

        Assertions.assertEquals(1, countEvents(dir), "the lengthened retention keeps the event");
        Assertions.assertTrue(messages.getLast().contains("1 changed"), messages.toString());

        // The same settings are not applied again.
        messages.clear();
        runtime = KansokushaRuntime.start(
            DuckDbStorageImpl.open(file), Path.of("unused-archive"), config, SERVER_KEY, messages::add,
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );
        runtime.maintenanceStarted().get(5, TimeUnit.SECONDS);
        runtime.close();
        Assertions.assertEquals(List.of(), messages);
    }

    @Test
    void testRetentionFingerprintIgnoresArchivingAndOrder() {
        var a = Key.key("example", "a");
        var b = Key.key("example", "b");
        var durations = new java.util.LinkedHashMap<Key, Duration>();
        durations.put(b, Duration.ofDays(2));
        durations.put(a, Duration.ofDays(1));
        Assertions.assertEquals(
            "default=86400000;example:a=86400000;example:b=172800000",
            KansokushaRuntime.retentionFingerprint(new KansokushaConfig.Retention(durations, Duration.ofDays(1), Set.of(a)))
        );
    }

    @Test
    void testQueryCallbacksDoNotRunOnStorageThread() throws Exception {
        var storage = new BlockingLookupStorage();
        var runtime = KansokushaRuntime.start(
            storage,
            Path.of("unused-archive"),
            config(10, BATCH_SIZE),
            SERVER_KEY,
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );

        try {
            var callbackThread = new AtomicReference<Thread>();
            var result = runtime.findEvent(UUID.fromString("0199a123-4567-789a-8bcd-ef0123456792"))
                .thenApply(found -> {
                    callbackThread.set(Thread.currentThread());
                    return found;
                });

            Assertions.assertTrue(storage.lookupStarted.await(5, TimeUnit.SECONDS));
            storage.lookupRelease.countDown();
            result.get(5, TimeUnit.SECONDS);

            Assertions.assertNotSame(storage.lookupThread.get(), callbackThread.get());
            Assertions.assertNotEquals("kansokusha-storage", callbackThread.get().getName());
        } finally {
            storage.lookupRelease.countDown();
            runtime.close();
        }
    }

    @Test
    void testEventLookupRunsOnStorageThreadWithoutBlockingCaller() throws Exception {
        var storage = new BlockingLookupStorage();
        var runtime = KansokushaRuntime.start(
            storage,
            Path.of("unused-archive"),
            config(10, BATCH_SIZE),
            SERVER_KEY,
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
            Path.of("unused-archive"),
            config(10, BATCH_SIZE),
            SERVER_KEY,
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );
        var request = new SearchRequest(
            SearchQueryParser.parse("", Clock.systemUTC(), ZoneOffset.UTC, null),
            new SearchRequest.Constraints(Set.of(EVENT_TYPE)),
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
                SearchQueryParser.parse("", Clock.systemUTC(), ZoneOffset.UTC, null),
                new SearchRequest.Constraints(Set.of(EVENT_TYPE)),
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
            runtime.maintenanceStarted().get(5, TimeUnit.SECONDS);
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

    @Test
    void testSearchMetadataIsCachedAndUpdatedByWrites(@TempDir Path dir) throws Exception {
        var other = Key.key("example", "other");
        var world = Key.key("example", "world");
        var storage = new CountingMetadataStorage(
            DuckDbStorageImpl.open(dir.resolve(KansokushaRuntime.DATABASE_FILENAME))
        );
        try (var runtime = KansokushaRuntime.start(
            storage,
            Path.of("unused-archive"),
            config(10, BATCH_SIZE),
            SERVER_KEY,
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        )) {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));
            runtime.registerEventType(new EventTypeDefinition(other, PayloadGeneration.FIRST));

            Assertions.assertTrue(runtime.searchMetadata().get(5, TimeUnit.SECONDS).events().isEmpty());
            Assertions.assertTrue(runtime.searchMetadata().get(5, TimeUnit.SECONDS).events().isEmpty());
            Assertions.assertEquals(1, storage.metadataCalls.get());

            var now = Instant.now();
            Assertions.assertTrue(runtime.submit(event(now)));
            Assertions.assertTrue(runtime.submit(new EventSubmission(
                other, PayloadGeneration.FIRST, now, SERVER_KEY, world, null, null, null,
                EventPayload.copyOf(new byte[]{1})
            )));

            // Queries run after the early flush on the same storage thread.
            var metadata = runtime.searchMetadata().get(5, TimeUnit.SECONDS);
            Assertions.assertEquals(Set.of(EVENT_TYPE, other), metadata.eventTypes());
            Assertions.assertEquals(Set.of(world), metadata.events().get(other).worlds());
            Assertions.assertEquals(1, storage.metadataCalls.get());
        }
    }

    private static final class CountingMetadataStorage implements Storage {

        private final Storage delegate;
        private final AtomicInteger metadataCalls = new AtomicInteger();

        private CountingMetadataStorage(Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(List<QueuedEvent> events) throws SQLException {
            this.delegate.append(events);
        }

        @Override
        public List<String> offlinePlayerNames() throws SQLException {
            return this.delegate.offlinePlayerNames();
        }

        @Override
        public Optional<EventDetail> findEvent(UUID eventId) throws SQLException {
            return this.delegate.findEvent(eventId);
        }

        @Override
        public SearchPage search(SearchRequest request) throws SQLException {
            return this.delegate.search(request);
        }

        @Override
        public long count(SearchRequest request) throws SQLException {
            return this.delegate.count(request);
        }

        @Override
        public SearchMetadata searchMetadata() throws SQLException {
            this.metadataCalls.incrementAndGet();
            return this.delegate.searchMetadata();
        }

        @Override
        public int deleteExpired(Instant now, Set<Key> archivedEventTypes) throws SQLException {
            return this.delegate.deleteExpired(now, archivedEventTypes);
        }

        @Override
        public void close() throws SQLException {
            this.delegate.close();
        }
    }

    @Test
    void testFlushYieldsToQueriesAndCloseWritesEverything() throws Exception {
        var storage = new OrderRecordingStorage();
        var runtime = KansokushaRuntime.start(
            storage,
            Path.of("unused-archive"),
            config(1000, BATCH_SIZE),
            SERVER_KEY,
            (message, failure) -> {
                throw new AssertionError(message, failure);
            }
        );
        var total = BATCH_SIZE * (KansokushaRuntime.MAX_BATCHES_PER_FLUSH * 2 + 1);

        try {
            runtime.registerEventType(new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST));

            // Occupies the storage thread so that the flush and the search are queued in this order.
            var blocker = runtime.findEvent(UUID.fromString("0199a123-4567-789a-8bcd-ef0123456792"));
            Assertions.assertTrue(storage.lookupStarted.await(5, TimeUnit.SECONDS));
            for (var i = 0; i < total; i++) {
                Assertions.assertTrue(runtime.submit(event(Instant.now())));
            }
            var search = runtime.searchMetadata();
            storage.lookupRelease.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            search.get(5, TimeUnit.SECONDS);

            Assertions.assertEquals(
                KansokushaRuntime.MAX_BATCHES_PER_FLUSH, storage.appendsBeforeMetadata.get(),
                "The query must run after one bounded flush, not after the queue is drained."
            );
        } finally {
            storage.lookupRelease.countDown();
            runtime.close();
        }

        Assertions.assertEquals(total, storage.appendedEvents.get());
    }

    private static final class OrderRecordingStorage implements Storage {

        private final CountDownLatch lookupStarted = new CountDownLatch(1);
        private final CountDownLatch lookupRelease = new CountDownLatch(1);
        private final AtomicInteger appends = new AtomicInteger();
        private final AtomicInteger appendedEvents = new AtomicInteger();
        private final AtomicInteger appendsBeforeMetadata = new AtomicInteger(-1);

        @Override
        public void append(List<QueuedEvent> events) {
            this.appends.incrementAndGet();
            this.appendedEvents.addAndGet(events.size());
        }

        @Override
        public List<String> offlinePlayerNames() {
            return List.of();
        }

        @Override
        public Optional<EventDetail> findEvent(UUID eventId) throws SQLException {
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
        public SearchPage search(SearchRequest request) {
            return new SearchPage(List.of(), Optional.empty(), Optional.empty());
        }

        @Override
        public long count(SearchRequest request) {
            return 0;
        }

        @Override
        public SearchMetadata searchMetadata() {
            this.appendsBeforeMetadata.set(this.appends.get());
            return SearchMetadata.empty();
        }

        @Override
        public int deleteExpired(Instant now, Set<Key> archivedEventTypes) {
            return 0;
        }

        @Override
        public void close() {
        }
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
        public List<String> offlinePlayerNames() {
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
        public int deleteExpired(Instant now, Set<Key> archivedEventTypes) {
            return 0;
        }

        @Override
        public long count(SearchRequest request) { return 0; }

        @Override
        public SearchMetadata searchMetadata() {
            return SearchMetadata.empty();
        }

        @Override
        public void close() {
        }
    }

    private static KansokushaRuntime start(Path dir, int queueCapacity) throws Exception {
        return start(dir, queueCapacity, BATCH_SIZE);
    }

    private static KansokushaRuntime start(Path dir, int queueCapacity, int batchSize) throws Exception {
        var storage = DuckDbStorageImpl.open(dir.resolve(KansokushaRuntime.DATABASE_FILENAME));
        return KansokushaRuntime.start(
            storage,
            Path.of("unused-archive"),
            config(queueCapacity, batchSize),
            SERVER_KEY,
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
            Duration.ofDays(1),
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
