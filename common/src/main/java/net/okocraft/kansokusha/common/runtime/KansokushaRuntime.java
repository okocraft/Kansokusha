package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.search.DatabaseHealth;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventTypeStatistics;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.storage.DuckDbStorage;
import net.okocraft.kansokusha.common.storage.OccurredRange;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import net.okocraft.kansokusha.common.storage.Storage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Implements {@link KansokushaApi} on top of a bounded queue and one storage thread.
 *
 * <p>Submitting threads only enqueue events. A single background thread writes queued events to
 * storage every flush interval, or as soon as a batch is full, and deletes expired events, so storage
 * access is never concurrent. Query results are delivered on separate threads, so the storage thread
 * is not occupied by whatever callers do with them.</p>
 */
@NotNullByDefault
public final class KansokushaRuntime implements KansokushaApi, EventSearchBackend, AutoCloseable {

    public static final String DATABASE_FILENAME = "kansokusha.duckdb";
    public static final String ARCHIVE_DIRECTORY = "archive";
    // Small enough that one range is rewritten quickly, so the write queue does not fill up meanwhile.
    private static final long MAX_EVENTS_PER_RECALCULATION = 100_000;
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'", Locale.ROOT);

    private static final int CLOSED_MASK = 1 << 31;
    // Bounds how long one flush task occupies the storage thread, so that queries queued behind it
    // are not starved while events keep arriving. The remainder is flushed by a follow-up task.
    /** The window of the recent event count shown by the health command. */
    public static final Duration RECENT_WINDOW = Duration.ofHours(24);

    static final int MAX_BATCHES_PER_FLUSH = 8;

    private final Optional<Key> localServerKey;
    private final KansokushaConfig.Retention retention;
    private final Path archiveDirectory;
    private final Storage storage;
    private final BiConsumer<String, Throwable> errorReporter;
    private final ConcurrentHashMap<Key, RegisteredEventType> eventTypes = new ConcurrentHashMap<>();
    private final int batchSize;
    private final BlockingQueue<QueuedEvent> queue;
    private final int queueCapacity;
    private final AtomicBoolean earlyFlushScheduled = new AtomicBoolean();
    // Avoids taking ArrayBlockingQueue's lock again just to check its size after offer().
    private final AtomicInteger queuedEvents = new AtomicInteger();
    private final AtomicLong lostEvents = new AtomicLong();
    private final Consumer<String> infoReporter;
    private final Duration cleanupInterval;
    private final Duration archiveInterval;
    private final CompletableFuture<Void> maintenance = new CompletableFuture<>();
    // Only accessed on the storage thread. The final cleanup on close must not use expiry that is not updated yet.
    private boolean maintenanceStarted;
    private final AtomicLong rejectedEvents = new AtomicLong();
    private final ScheduledExecutorService storageThread;
    // Runs the callbacks of query results so that rendering never occupies the storage thread.
    private final ExecutorService callbackExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("kansokusha-callback-", 0).factory()
    );
    // Only accessed on the storage thread.
    private final SearchMetadataCache metadataCache = new SearchMetadataCache();
    // The sign bit marks the runtime closed; the remaining value counts in-flight submissions.
    private final AtomicInteger submissionState = new AtomicInteger();
    private final Object closeMonitor = new Object();

    private KansokushaRuntime(
        @Nullable Key localServerKey,
        KansokushaConfig config,
        Storage storage,
        Path archiveDirectory,
        Consumer<String> infoReporter,
        BiConsumer<String, Throwable> errorReporter
    ) {
        this.infoReporter = infoReporter;
        this.cleanupInterval = config.cleanupInterval();
        this.archiveInterval = config.archiveInterval();
        this.localServerKey = Optional.ofNullable(localServerKey);
        this.retention = config.retention();
        this.storage = storage;
        this.archiveDirectory = archiveDirectory;
        this.errorReporter = errorReporter;
        this.batchSize = config.batchSize();
        this.queueCapacity = config.queueCapacity();
        this.queue = new ArrayBlockingQueue<>(this.queueCapacity);
        this.storageThread = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("kansokusha-storage").factory()
        );
    }

    /**
     * Opens the database in the data directory and starts the storage thread.
     *
     * @param localServerKey the local server identity, or {@code null} for proxies
     * @param infoReporter   receives progress of long-running maintenance, such as applying changed retention
     * @param errorReporter  receives storage failures so that administrators can notice them
     */
    public static KansokushaRuntime start(
        Path dataDirectory,
        KansokushaConfig config,
        @Nullable Key localServerKey,
        Consumer<String> infoReporter,
        BiConsumer<String, Throwable> errorReporter
    ) throws IOException, SQLException {
        var storage = DuckDbStorage.open(
            dataDirectory,
            dataDirectory.resolve(DATABASE_FILENAME),
            config.duckDb().memoryLimit().orElse(""),
            config.duckDb().threads()
        );
        return start(
            storage, dataDirectory.resolve(ARCHIVE_DIRECTORY), config, localServerKey, infoReporter, errorReporter
        );
    }

    static KansokushaRuntime start(
        Storage storage,
        Path archiveDirectory,
        KansokushaConfig config,
        @Nullable Key localServerKey,
        BiConsumer<String, Throwable> errorReporter
    ) {
        return start(storage, archiveDirectory, config, localServerKey, message -> {
        }, errorReporter);
    }

    static KansokushaRuntime start(
        Storage storage,
        Path archiveDirectory,
        KansokushaConfig config,
        @Nullable Key localServerKey,
        Consumer<String> infoReporter,
        BiConsumer<String, Throwable> errorReporter
    ) {
        var runtime = new KansokushaRuntime(
            localServerKey, config, storage, archiveDirectory, infoReporter, errorReporter
        );

        var flushMillis = config.flushInterval().toMillis();
        runtime.storageThread.scheduleWithFixedDelay(
            runtime::flush,
            flushMillis,
            flushMillis,
            TimeUnit.MILLISECONDS
        );
        // Cleanup and archiving start only once stored events have the expiry of the current retention
        // settings, so that a lengthened retention period is not cut short by the old expiry.
        runtime.storageThread.execute(runtime::applyRetentionThenStartMaintenance);
        return runtime;
    }

    // Runs on the storage thread.
    private void applyRetentionThenStartMaintenance() {
        var retention = retentionFingerprint(this.retention);
        try {
            if (this.storage.appliedRetention().filter(retention::equals).isPresent()) {
                this.startMaintenance();
                return;
            }
        } catch (SQLException | RuntimeException e) {
            this.reportRetentionFailure(e);
            return;
        }

        this.infoReporter.accept(
            "The retention settings differ from the ones applied to the stored events. Recalculating their expiry"
                + " before deleting or archiving expired events; this can take a while on a large database."
        );
        var retentionMillis = new HashMap<Key, Long>();
        this.retention.durations().forEach((eventType, duration) -> retentionMillis.put(eventType, duration.toMillis()));
        var defaultMillis = this.retention.defaultDuration().toMillis();
        try {
            // Finding the ranges scans every event, so it runs on its own connection off the storage thread.
            this.callbackExecutor.execute(() -> {
                try {
                    var ranges = this.storage.expiryRecalculationRanges(MAX_EVENTS_PER_RECALCULATION);
                    this.storageThread.execute(
                        () -> this.recalculateExpiry(ranges, 0, 0, retentionMillis, defaultMillis, retention)
                    );
                } catch (RejectedExecutionException ignored) {
                    // Stopping; the next start recalculates again.
                } catch (SQLException | RuntimeException e) {
                    this.reportRetentionFailure(e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Stopping; the next start recalculates again.
        }
    }

    // Runs on the storage thread. Each range is a separate task, so flushes that became due in the meantime
    // run between ranges and the write queue keeps draining.
    private void recalculateExpiry(
        List<OccurredRange> ranges,
        int index,
        long changed,
        Map<Key, Long> retentionMillis,
        long defaultMillis,
        String retention
    ) {
        try {
            if (index == ranges.size()) {
                this.storage.recordAppliedRetention(retention);
                this.infoReporter.accept("Recalculated the expiry of stored events; " + changed + " changed.");
                this.startMaintenance();
                return;
            }
            var range = ranges.get(index);
            var total = changed + this.storage.recalculateExpiry(range.from(), range.to(), retentionMillis, defaultMillis);
            this.storageThread.execute(
                () -> this.recalculateExpiry(ranges, index + 1, total, retentionMillis, defaultMillis, retention)
            );
        } catch (RejectedExecutionException ignored) {
            // Stopping; the next start recalculates again. Ranges already done are skipped as unchanged.
        } catch (SQLException | RuntimeException e) {
            this.reportRetentionFailure(e);
        }
    }

    private void reportRetentionFailure(Exception failure) {
        if ((this.submissionState.get() & CLOSED_MASK) != 0) {
            return;
        }
        this.errorReporter.accept(
            "Failed to apply the retention settings to stored events. Expired events are neither deleted nor"
                + " archived until Kansokusha starts again and succeeds.",
            failure
        );
    }

    // Runs on the storage thread.
    private void startMaintenance() {
        this.maintenanceStarted = true;
        this.storageThread.scheduleWithFixedDelay(
            this::deleteExpired,
            0,
            this.cleanupInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        if (!this.retention.archivedEventTypes().isEmpty()) {
            // Also runs at startup, so that servers restarting more often than the interval still archive.
            this.storageThread.scheduleWithFixedDelay(
                this::archiveExpired,
                0,
                this.archiveInterval.toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
        this.maintenance.complete(null);
    }

    // Durations only: whether a policy archives does not change expires_at.
    static String retentionFingerprint(KansokushaConfig.Retention retention) {
        var fingerprint = new StringBuilder("default=").append(retention.defaultDuration().toMillis());
        new TreeMap<>(retention.durations().entrySet().stream().collect(
            java.util.stream.Collectors.toMap(entry -> entry.getKey().asString(), entry -> entry.getValue().toMillis())
        )).forEach((eventType, millis) -> fingerprint.append(';').append(eventType).append('=').append(millis));
        return fingerprint.toString();
    }

    /** Completes once cleanup and archiving have started. */
    CompletableFuture<Void> maintenanceStarted() {
        return this.maintenance;
    }

    @Override
    public Optional<Key> localServerKey() {
        return this.localServerKey;
    }

    @Override
    public void registerEventType(EventTypeDefinition definition) {
        var registered = new RegisteredEventType(
            definition.payloadGeneration(),
            this.retention.durationOf(definition.key()).toMillis()
        );
        var existing = this.eventTypes.putIfAbsent(definition.key(), registered);
        if (existing != null && !existing.payloadGeneration().equals(definition.payloadGeneration())) {
            throw new IllegalArgumentException(
                definition.key().asString() + " is already registered with payload generation "
                    + existing.payloadGeneration().value()
            );
        }
    }

    @Override
    public boolean submit(EventSubmission submission) {
        return this.enqueue(submission, null, null);
    }

    @Override
    public boolean submitSearchable(EventSubmission submission, String searchText) {
        Objects.requireNonNull(searchText, "searchText");
        return this.enqueue(submission, null, searchText);
    }

    @Override
    public boolean submitPlayerLogin(EventSubmission submission, String username) {
        if (!(submission.actor() instanceof PlayerActor)) {
            throw new IllegalArgumentException("A player login must use PlayerActor.");
        }
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username must not be empty");
        }
        return this.enqueue(submission, username, null);
    }

    @Override
    public CompletableFuture<List<String>> offlinePlayerNames() {
        return this.queryStorage(this.storage::offlinePlayerNames);
    }

    @Override
    public CompletableFuture<SearchPage> search(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        // Deliberately do not flush the write queue here. Search only sees committed storage state.
        return this.queryStorage(() -> this.storage.search(request));
    }

    @Override
    public CompletableFuture<Long> count(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        return this.queryStorage(() -> this.storage.count(request));
    }

    @Override
    public CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return this.queryStorage(() -> this.storage.findEvent(eventId));
    }

    @Override
    public CompletableFuture<SearchMetadata> searchMetadata() {
        return this.queryStorage(() -> {
            if (!this.metadataCache.isLoaded()) {
                this.metadataCache.load(this.storage.searchMetadata());
            }
            return this.metadataCache.snapshot();
        });
    }

    @Override
    public CompletableFuture<DatabaseHealth> health() {
        return this.queryStorage(() -> new DatabaseHealth(
            this.storage.health(),
            new DatabaseHealth.Queue(
                this.queuedEvents.get(),
                this.queueCapacity,
                this.lostEvents.get(),
                this.rejectedEvents.get()
            )
        ));
    }

    @Override
    public CompletableFuture<Map<Key, EventTypeStatistics>> storedEventStatistics() {
        // The scan can take a long time, so it runs outside the storage thread.
        var result = new CompletableFuture<Map<Key, EventTypeStatistics>>();
        try {
            this.callbackExecutor.execute(() -> {
                try {
                    result.complete(this.storage.eventStatisticsByType(Instant.now().minus(RECENT_WINDOW)));
                } catch (SQLException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private RegisteredEventType requireRegisteredEventType(EventSubmission submission) {
        var registered = this.eventTypes.get(submission.eventType());
        if (registered == null || !submission.payloadGeneration().equals(registered.payloadGeneration())) {
            throw new IllegalArgumentException(
                submission.eventType().asString() + " is not registered with payload generation "
                    + submission.payloadGeneration().value()
            );
        }
        return registered;
    }

    private boolean enqueue(
        EventSubmission submission,
        @Nullable String playerName,
        @Nullable String searchText
    ) {
        var registered = this.requireRegisteredEventType(submission);
        // Validate here so that one invalid event cannot make the whole batch fail to write.
        var queued = toQueuedEvent(submission, registered.retentionMillis(), playerName, searchText);
        if (!this.beginSubmission()) {
            return false;
        }
        try {
            if (!this.queue.offer(queued)) {
                this.rejectedEvents.incrementAndGet();
                return false;
            }
            var queuedEvents = this.queuedEvents.incrementAndGet();
            if (queuedEvents >= this.batchSize && this.earlyFlushScheduled.compareAndSet(false, true)) {
                this.storageThread.execute(this::flush);
            }
            return true;
        } finally {
            this.endSubmission();
        }
    }

    private static QueuedEvent toQueuedEvent(
        EventSubmission submission,
        long retentionMillis,
        @Nullable String playerName,
        @Nullable String searchText
    ) {
        try {
            // toEpochMilli() rounds towards negative infinity, the same as truncating to milliseconds.
            var occurredAtMillis = submission.occurredAt().toEpochMilli();
            return new QueuedEvent(
                submission,
                requireStorableMillis(occurredAtMillis),
                requireStorableMillis(Math.addExact(occurredAtMillis, retentionMillis)),
                playerName,
                searchText
            );
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("occurredAt is out of range: " + submission.occurredAt(), e);
        }
    }

    private <T> CompletableFuture<T> queryStorage(StorageQuery<T> query) {
        var result = new CompletableFuture<T>();
        try {
            this.storageThread.execute(() -> {
                try {
                    this.complete(result, query.get(), null);
                } catch (SQLException | RuntimeException e) {
                    this.complete(result, null, e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("Kansokusha is stopped.", e));
        }
        return result;
    }

    // Dependent stages of the future run on the thread that completes it. Complete it on the callback
    // executor so that callers' processing, such as decoding and rendering events, stays off the storage thread.
    private <T> void complete(CompletableFuture<T> result, @Nullable T value, @Nullable Throwable failure) {
        Runnable completion = failure != null
            ? () -> result.completeExceptionally(failure)
            : () -> result.complete(value);
        try {
            this.callbackExecutor.execute(completion);
        } catch (RejectedExecutionException e) {
            completion.run();
        }
    }

    // DuckDB uses the minimum and maximum values as -infinity and infinity.
    private static long requireStorableMillis(long millis) {
        if (millis == Long.MIN_VALUE || millis == Long.MAX_VALUE) {
            throw new ArithmeticException("reserved timestamp value");
        }
        return millis;
    }

    private boolean beginSubmission() {
        while (true) {
            var state = this.submissionState.get();
            if ((state & CLOSED_MASK) != 0) {
                return false;
            }
            if (this.submissionState.compareAndSet(state, state + 1)) {
                return true;
            }
        }
    }

    private void endSubmission() {
        if (this.submissionState.decrementAndGet() == CLOSED_MASK) {
            synchronized (this.closeMonitor) {
                this.closeMonitor.notifyAll();
            }
        }
    }

    /**
     * Stops accepting events, writes the queued ones, and closes the database.
     */
    @Override
    public void close() {
        if (!this.beginClose()) {
            return;
        }
        this.awaitSubmissions();

        // Keep the final flush and close on the storage thread. DuckDBAppender is thread-confined
        // to the thread that created it, even when calls are not concurrent.
        this.storageThread.execute(() -> {
            this.flush(Integer.MAX_VALUE);
            if (this.maintenanceStarted) {
                this.deleteExpired();
            }
            try {
                this.storage.close();
            } catch (SQLException e) {
                this.errorReporter.accept("Failed to close the Kansokusha database.", e);
            }
        });
        this.storageThread.close();
        this.callbackExecutor.close();
    }

    private boolean beginClose() {
        while (true) {
            var state = this.submissionState.get();
            if ((state & CLOSED_MASK) != 0) {
                return false;
            }
            if (this.submissionState.compareAndSet(state, state | CLOSED_MASK)) {
                return true;
            }
        }
    }

    private void awaitSubmissions() {
        var interrupted = false;
        synchronized (this.closeMonitor) {
            while (this.submissionState.get() != CLOSED_MASK) {
                try {
                    this.closeMonitor.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void flush() {
        this.flush(MAX_BATCHES_PER_FLUSH);
    }

    private void flush(int maxBatches) {
        this.earlyFlushScheduled.set(false);
        var batch = new ArrayList<QueuedEvent>(this.batchSize);
        int drained;
        var batches = 0;
        while (batches < maxBatches && (drained = this.queue.drainTo(batch, this.batchSize)) > 0) {
            batches++;
            this.queuedEvents.addAndGet(-drained);
            try {
                this.storage.append(batch);
                this.metadataCache.add(batch);
            } catch (SQLException | RuntimeException e) {
                this.lostEvents.addAndGet(batch.size());
                this.errorReporter.accept("Failed to write " + batch.size() + " events; they are lost.", e);
            }
            batch.clear();
        }

        if (batches == maxBatches && !this.queue.isEmpty()) {
            // Goes behind the queries that arrived in the meantime.
            try {
                this.storageThread.execute(this::flush);
            } catch (RejectedExecutionException ignored) {
                // The runtime is closing, and close() flushes the rest.
            }
        }
    }

    private void deleteExpired() {
        try {
            if (this.storage.deleteExpired(Instant.now(), this.retention.archivedEventTypes()) > 0) {
                this.metadataCache.invalidate();
            }
        } catch (SQLException | RuntimeException e) {
            this.errorReporter.accept("Failed to delete expired events.", e);
        }
    }

    private void archiveExpired() {
        var now = Instant.now();
        var file = archiveFile(this.archiveDirectory, now);
        try {
            if (this.storage.archiveExpired(now, this.retention.archivedEventTypes(), file) > 0) {
                this.metadataCache.invalidate();
            }
        } catch (SQLException | IOException | RuntimeException e) {
            this.errorReporter.accept("Failed to archive expired events to " + file + ".", e);
        }
    }

    static Path archiveFile(Path archiveDirectory, Instant now) {
        var utc = now.atOffset(ZoneOffset.UTC);
        return archiveDirectory
            .resolve(String.format(Locale.ROOT, "%04d", utc.getYear()))
            .resolve(String.format(Locale.ROOT, "%02d", utc.getMonthValue()))
            .resolve("events-" + ARCHIVE_TIMESTAMP.format(utc) + ".parquet");
    }

    // Resolves the retention period at registration instead of on every submission.
    private record RegisteredEventType(PayloadGeneration payloadGeneration, long retentionMillis) {
    }

    @FunctionalInterface
    private interface StorageQuery<T> {

        T get() throws SQLException;
    }
}
