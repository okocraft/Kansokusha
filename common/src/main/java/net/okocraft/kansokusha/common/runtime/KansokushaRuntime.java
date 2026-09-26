package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.storage.DuckDbStorage;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import net.okocraft.kansokusha.common.storage.Storage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
public final class KansokushaRuntime implements KansokushaApi, AutoCloseable {

    public static final String DATABASE_FILENAME = "kansokusha.duckdb";

    private static final int CLOSED_MASK = 1 << 31;

    private final Optional<Key> localServerKey;
    private final KansokushaConfig.Retention retention;
    private final Storage storage;
    private final BiConsumer<String, Throwable> errorReporter;
    private final ConcurrentHashMap<Key, PayloadGeneration> eventTypes = new ConcurrentHashMap<>();
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
        var existing = this.eventTypes.putIfAbsent(definition.key(), definition.payloadGeneration());
        if (existing != null && !existing.equals(definition.payloadGeneration())) {
            throw new IllegalArgumentException(
                definition.key().asString() + " is already registered with payload generation " + existing.value()
            );
        }
    }

    @Override
    public boolean submit(EventSubmission submission) {
        var generation = this.eventTypes.get(submission.eventType());
        if (!submission.payloadGeneration().equals(generation)) {
            throw new IllegalArgumentException(
                submission.eventType().asString() + " is not registered with payload generation "
                    + submission.payloadGeneration().value()
            );
        }
        // Validate here so that one invalid event cannot make the whole batch fail to write.
        var queued = this.toQueuedEvent(submission);

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

    private QueuedEvent toQueuedEvent(EventSubmission submission) {
        try {
            var occurredAt = submission.occurredAt().truncatedTo(ChronoUnit.MILLIS);
            var expiresAt = occurredAt.plus(this.retention.durationOf(submission.eventType()));
            return new QueuedEvent(
                submission,
                requireStorableMillis(occurredAt.toEpochMilli()),
                requireStorableMillis(expiresAt.toEpochMilli())
            );
        } catch (ArithmeticException | DateTimeException e) {
            throw new IllegalArgumentException("occurredAt is out of range: " + submission.occurredAt(), e);
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

        // Waits for a running flush or cleanup so that the connection is never used concurrently.
        this.storageThread.close();
        this.flush();
        try {
            this.storage.close();
        } catch (SQLException e) {
            this.errorReporter.accept("Failed to close the Kansokusha database.", e);
        }
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
}
