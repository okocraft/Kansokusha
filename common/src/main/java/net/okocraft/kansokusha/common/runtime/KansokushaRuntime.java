package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.storage.DuckDbStorage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

/**
 * Implements {@link KansokushaApi} on top of a bounded queue and one storage thread.
 *
 * <p>Submitting threads only enqueue events. A single background thread periodically writes queued
 * events to DuckDB and deletes expired events, so storage access is never concurrent.</p>
 */
@NotNullByDefault
public final class KansokushaRuntime implements KansokushaApi, AutoCloseable {

    public static final String DATABASE_FILENAME = "kansokusha.duckdb";

    private final Optional<Key> localServerKey;
    private final KansokushaConfig.Retention retention;
    private final DuckDbStorage storage;
    private final BiConsumer<String, Throwable> errorReporter;
    private final ConcurrentHashMap<Key, PayloadGeneration> eventTypes = new ConcurrentHashMap<>();
    private final BlockingQueue<EventSubmission> queue;
    private final ScheduledExecutorService storageThread;
    // Guarantees that no event enters the queue after close() has drained it.
    private final ReadWriteLock closeLock = new ReentrantReadWriteLock();
    private boolean closed;

    private KansokushaRuntime(
        @Nullable Key localServerKey,
        KansokushaConfig config,
        DuckDbStorage storage,
        BiConsumer<String, Throwable> errorReporter
    ) {
        this.localServerKey = Optional.ofNullable(localServerKey);
        this.retention = config.retention();
        this.storage = storage;
        this.errorReporter = errorReporter;
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
        var storage = DuckDbStorage.open(dataDirectory.resolve(DATABASE_FILENAME));
        var runtime = new KansokushaRuntime(localServerKey, config, storage, errorReporter);

        var flushMillis = config.flushInterval().toMillis();
        runtime.storageThread.scheduleWithFixedDelay(runtime::flush, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
        runtime.storageThread.scheduleWithFixedDelay(
            runtime::deleteExpired, 0, config.cleanupInterval().toMillis(), TimeUnit.MILLISECONDS
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

        var lock = this.closeLock.readLock();
        lock.lock();
        try {
            return !this.closed && this.queue.offer(submission);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops accepting events, writes the queued ones, and closes the database.
     */
    @Override
    public void close() {
        var lock = this.closeLock.writeLock();
        lock.lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
        } finally {
            lock.unlock();
        }

        // Waits for a running flush or cleanup so that the connection is never used concurrently.
        this.storageThread.close();
        this.flush();
        try {
            this.storage.close();
        } catch (SQLException e) {
            this.errorReporter.accept("Failed to close the Kansokusha database.", e);
        }
    }

    private void flush() {
        var batch = new ArrayList<EventSubmission>(this.queue.size());
        this.queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }

        try {
            this.storage.append(batch, this.retention);
        } catch (SQLException | RuntimeException e) {
            this.errorReporter.accept("Failed to write " + batch.size() + " events; they are lost.", e);
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
