package net.okocraft.kansokusha.common.writer;

import net.okocraft.kansokusha.common.api.BoundedEventIntake;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import net.okocraft.kansokusha.common.storage.EventBatchWriter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
@NotNullByDefault
public final class AsyncBatchWriterService {

    private final BoundedEventIntake intake;
    private final EventBatchWriter writer;
    private final int maxBatchSize;
    private final long maxBatchDelayNanos;
    private final NanoClock clock;
    private final EventPoller poller;
    private final Object lifecycleMonitor = new Object();

    private volatile State state = State.NEW;
    private volatile boolean stopRequested;
    @Nullable
    private volatile Throwable failureCause;
    @Nullable
    private Thread worker;

    public AsyncBatchWriterService(
        BoundedEventIntake intake,
        EventBatchWriter writer,
        int maxBatchSize,
        Duration maxBatchDelay
    ) {
        this(
            intake,
            writer,
            maxBatchSize,
            maxBatchDelay,
            System::nanoTime,
            (source, timeoutNanos) -> source.poll(timeoutNanos, TimeUnit.NANOSECONDS)
        );
    }

    AsyncBatchWriterService(
        BoundedEventIntake intake,
        EventBatchWriter writer,
        int maxBatchSize,
        Duration maxBatchDelay,
        NanoClock clock,
        EventPoller poller
    ) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive.");
        }
        Objects.requireNonNull(maxBatchDelay, "maxBatchDelay");
        if (maxBatchDelay.isZero() || maxBatchDelay.isNegative()) {
            throw new IllegalArgumentException("maxBatchDelay must be positive.");
        }
        if (maxBatchDelay.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("maxBatchDelay must use whole milliseconds.");
        }

        final long delayNanos;
        try {
            delayNanos = maxBatchDelay.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("maxBatchDelay exceeds the supported nanosecond range.", e);
        }

        this.intake = Objects.requireNonNull(intake, "intake");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.maxBatchSize = maxBatchSize;
        this.maxBatchDelayNanos = delayNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.poller = Objects.requireNonNull(poller, "poller");
    }

    public void start() {
        synchronized (this.lifecycleMonitor) {
            if (this.state != State.NEW) {
                throw new IllegalStateException("Writer service can only be started once.");
            }

            this.worker = Thread.ofPlatform()
                .name("kansokusha-event-writer")
                .unstarted(this::runLoop);
            this.state = State.RUNNING;
            this.worker.start();
        }
    }

    public void requestStop() {
        Thread threadToInterrupt = null;

        synchronized (this.lifecycleMonitor) {
            if (this.state == State.NEW) {
                this.stopRequested = true;
                this.state = State.STOPPED;
                return;
            }
            if (this.state != State.RUNNING) {
                return;
            }

            this.stopRequested = true;
            this.state = State.STOPPING;
            threadToInterrupt = this.worker;
        }

        if (threadToInterrupt != null) {
            threadToInterrupt.interrupt();
        }
    }

    public void awaitStopped() throws InterruptedException {
        final Thread currentWorker;
        synchronized (this.lifecycleMonitor) {
            currentWorker = this.worker;
        }
        if (currentWorker != null) {
            currentWorker.join();
        }
    }

    public State state() {
        return this.state;
    }

    public Optional<Throwable> failureCause() {
        return Optional.ofNullable(this.failureCause);
    }

    private void runLoop() {
        Throwable failure = null;

        try {
            while (!this.stopRequested) {
                AcceptedEvent first;
                try {
                    first = this.intake.take();
                } catch (InterruptedException e) {
                    if (this.stopRequested) {
                        break;
                    }
                    continue;
                }

                var batch = new ArrayList<AcceptedEvent>();
                batch.add(first);
                var batchStartedAt = this.clock.nanoTime();

                while (batch.size() < this.maxBatchSize && !this.stopRequested) {
                    var elapsed = this.clock.nanoTime() - batchStartedAt;
                    var remaining = this.maxBatchDelayNanos - elapsed;
                    if (remaining <= 0) {
                        break;
                    }

                    final AcceptedEvent next;
                    try {
                        next = this.poller.poll(this.intake, remaining);
                    } catch (InterruptedException e) {
                        if (this.stopRequested) {
                            break;
                        }
                        continue;
                    }

                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }

                this.writeBatch(batch);
            }
        } catch (SQLException | RuntimeException e) {
            failure = e;
        } finally {
            synchronized (this.lifecycleMonitor) {
                if (failure != null) {
                    this.failureCause = failure;
                    this.state = State.FAILED;
                } else {
                    this.state = State.STOPPED;
                }
            }
        }
    }

    private void writeBatch(List<AcceptedEvent> batch) throws SQLException {
        var immutableBatch = List.copyOf(batch);
        var written = this.writer.append(immutableBatch);
        if (written != immutableBatch.size()) {
            throw new SQLException(
                "Event batch writer persisted " + written + " of " + immutableBatch.size() + " events."
            );
        }
    }

    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    @FunctionalInterface
    interface NanoClock {

        long nanoTime();
    }

    @FunctionalInterface
    interface EventPoller {

        @Nullable
        AcceptedEvent poll(BoundedEventIntake intake, long timeoutNanos) throws InterruptedException;
    }
}
