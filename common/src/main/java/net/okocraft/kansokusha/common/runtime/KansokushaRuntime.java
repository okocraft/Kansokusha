package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.storage.DuckDbStorage;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import net.okocraft.kansokusha.common.storage.Storage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Implements {@link KansokushaApi} on top of a bounded queue and one storage thread.
 *
 * <p>Submitting threads only enqueue events. A single background thread writes queued events to
 * storage every flush interval, or as soon as a batch is full, and deletes expired events, so storage
 * access is never concurrent.</p>
 */
@NotNullByDefault
public final class KansokushaRuntime implements KansokushaApi, EventSearchBackend, AutoCloseable {

    public static final String DATABASE_FILENAME = "kansokusha.duckdb";

    private static final int CLOSED_MASK = 1 << 31;

    private final Optional<Key> localServerKey;
    private final KansokushaConfig.Retention retention;
    private final Storage storage;
    private final BiConsumer<String, Throwable> errorReporter;
    private final ConcurrentHashMap<Key, RegisteredEventType> eventTypes = new ConcurrentHashMap<>();
    private final int batchSize;
    private final BlockingQueue<QueuedEvent> queue;
    private final AtomicBoolean earlyFlushScheduled = new AtomicBoolean();
    // Avoids taking ArrayBlockingQueue's lock again just to check its size after offer().
    private final AtomicInteger queuedEvents = new AtomicInteger();
    private final ScheduledExecutorService storageThread;
    // The sign bit marks the runtime closed; the remaining value counts in-flight submissions.
    private final AtomicInteger submissionState = new AtomicInteger();
    private final Object closeMonitor = new Object();

    private KansokushaRuntime(
        @Nullable Key localServerKey,
        KansokushaConfig config,
        Storage storage,
        BiConsumer<String, Throwable> errorReporter
    ) {
        this.localServerKey = Optional.ofNullable(localServerKey);
        this.retention = config.retention();
        this.storage = storage;
        this.errorReporter = errorReporter;
        this.batchSize = config.batchSize();
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.storageThread = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("kansokusha-storage").factory()
        );
    }

    /**
     * Opens the database in the data directory and starts the storage thread.
     *
     * @param localServerKey the local server identity, or {@code null} for proxies
     * @param errorReporter  receives storage failures so that administrators can notice them
     */
    public static KansokushaRuntime start(
        Path dataDirectory,
        KansokushaConfig config,
        @Nullable Key localServerKey,
        BiConsumer<String, Throwable> errorReporter
    ) throws IOException, SQLException {
        var storage = DuckDbStorage.open(
            dataDirectory,
            dataDirectory.resolve(DATABASE_FILENAME)
        );
        return start(storage, config, localServerKey, errorReporter);
    }

    static KansokushaRuntime start(
        Storage storage,
        KansokushaConfig config,
        @Nullable Key localServerKey,
        BiConsumer<String, Throwable> errorReporter
    ) {
        var runtime = new KansokushaRuntime(localServerKey, config, storage, errorReporter);

        var flushMillis = config.flushInterval().toMillis();
        runtime.storageThread.scheduleWithFixedDelay(
            runtime::flush,
            flushMillis,
            flushMillis,
            TimeUnit.MILLISECONDS
        );
        runtime.storageThread.scheduleWithFixedDelay(
            runtime::deleteExpired,
            0,
            config.cleanupInterval().toMillis(),
            TimeUnit.MILLISECONDS
        );
        return runtime;
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
    public CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return this.queryStorage(() -> this.storage.findEvent(eventId));
    }

    @Override
    public CompletableFuture<SearchMetadata> searchMetadata() {
        return this.queryStorage(this.storage::searchMetadata);
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
                    result.complete(query.get());
                } catch (SQLException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("Kansokusha is stopped.", e));
        }
        return result;
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
            this.flush();
            this.deleteExpired();
            try {
                this.storage.close();
            } catch (SQLException e) {
                this.errorReporter.accept("Failed to close the Kansokusha database.", e);
            }
        });
        this.storageThread.close();
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
        this.earlyFlushScheduled.set(false);
        var batch = new ArrayList<QueuedEvent>(this.batchSize);
        int drained;
        while ((drained = this.queue.drainTo(batch, this.batchSize)) > 0) {
            this.queuedEvents.addAndGet(-drained);
            try {
                this.storage.append(batch);
            } catch (SQLException | RuntimeException e) {
                this.errorReporter.accept("Failed to write " + batch.size() + " events; they are lost.", e);
            }
            batch.clear();
        }
    }

    private void deleteExpired() {
        try {
            this.storage.deleteExpired(Instant.now());
        } catch (SQLException | RuntimeException e) {
            this.errorReporter.accept("Failed to delete expired events.", e);
        }
    }

    // Resolves the retention period at registration instead of on every submission.
    private record RegisteredEventType(PayloadGeneration payloadGeneration, long retentionMillis) {
    }

    @FunctionalInterface
    private interface StorageQuery<T> {

        T get() throws SQLException;
    }
}
