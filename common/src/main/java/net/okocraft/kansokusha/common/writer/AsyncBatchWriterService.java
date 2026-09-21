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
public final class AsyncBatchWriterService implements AutoCloseable {

    private final BoundedEventIntake intake;
    private final EventBatchWriter writer;
    private final PipelineFailureReporter failureReporter;
    private final int maxBatchSize;
    private final long maxBatchDelayNanos;
    private final NanoClock clock;
    private final EventPoller poller;
    private final Object lifecycleMonitor = new Object();

    private volatile State state = State.NEW;
    private volatile boolean stopRequested;
    private volatile boolean drainRequested;
    @Nullable
    private volatile Throwable failureCause;
    @Nullable
    private Thread worker;

    public AsyncBatchWriterService(
        BoundedEventIntake intake,
        EventBatchWriter writer,
        PipelineFailureReporter failureReporter,
        int maxBatchSize,
        Duration maxBatchDelay
    ) {
        this(
            intake,
            writer,
            failureReporter,
            maxBatchSize,
            maxBatchDelay,
            System::nanoTime,
            (source, timeoutNanos) -> source.poll(timeoutNanos, TimeUnit.NANOSECONDS)
        );
    }

    AsyncBatchWriterService(
        BoundedEventIntake intake,
        EventBatchWriter writer,
        PipelineFailureReporter failureReporter,
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
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
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

            this.worker = this.newWorker();
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
            if (this.state != State.RUNNING && this.state != State.DRAINING) {
                return;
            }

            this.drainRequested = false;
            this.stopRequested = true;
            this.state = State.STOPPING;
            threadToInterrupt = this.worker;
        }

        if (threadToInterrupt != null) {
            threadToInterrupt.interrupt();
        }
    }

    public void drainAndStop() throws InterruptedException {
        this.intake.beginDraining();

        final Thread threadToJoin;
        final boolean interruptWorker;

        synchronized (this.lifecycleMonitor) {
            switch (this.state) {
                case NEW -> {
                    this.drainRequested = true;
                    this.state = State.DRAINING;
                    this.worker = this.newWorker();
                    this.worker.start();
                    threadToJoin = this.worker;
                    interruptWorker = false;
                }
                case RUNNING -> {
                    this.drainRequested = true;
                    this.state = State.DRAINING;
                    threadToJoin = this.worker;
                    interruptWorker = true;
                }
                case DRAINING -> {
                    threadToJoin = this.worker;
                    interruptWorker = true;
                }
                case STOPPING, STOPPED, FAILED -> {
                    threadToJoin = this.worker;
                    interruptWorker = false;
                }
            }
        }

        if (interruptWorker && threadToJoin != null) {
            threadToJoin.interrupt();
        }
        if (threadToJoin != null) {
            threadToJoin.join();
        }

        this.intake.close();
    }

    @Override
    public void close() throws InterruptedException {
        this.drainAndStop();
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

    private Thread newWorker() {
        return Thread.ofPlatform()
            .name("kansokusha-event-writer")
            .unstarted(this::runLoop);
    }

    private void runLoop() {
        Throwable failure = null;

        try {
            while (true) {
                var first = this.awaitFirstEvent();
                if (first == null) {
                    break;
                }

                var batch = new ArrayList<AcceptedEvent>(this.maxBatchSize);
                batch.add(first);

                if (this.drainRequested) {
                    this.fillDrainBatch(batch);
                } else {
                    this.fillTimedBatch(batch);
                    if (this.drainRequested) {
                        this.fillDrainBatch(batch);
                    }
                }

                this.writeBatch(batch);
            }
        } catch (SQLException | RuntimeException e) {
            failure = e;
        } catch (Error e) {
            failure = e;
            throw e;
        } finally {
            if (failure != null) {
                this.transitionToFailed(failure);
            } else {
                synchronized (this.lifecycleMonitor) {
                    this.state = State.STOPPED;
                }
            }
        }
    }

    @Nullable
    private AcceptedEvent awaitFirstEvent() {
        while (true) {
            if (this.stopRequested && !this.drainRequested) {
                return null;
            }
            if (this.drainRequested) {
                return this.intake.poll();
            }

            try {
                return this.intake.take();
            } catch (InterruptedException e) {
                if (this.stopRequested && !this.drainRequested) {
                    return null;
                }
            }
        }
    }

    private void fillTimedBatch(List<AcceptedEvent> batch) {
        var batchStartedAt = this.clock.nanoTime();

        while (batch.size() < this.maxBatchSize && !this.stopRequested && !this.drainRequested) {
            var elapsed = this.clock.nanoTime() - batchStartedAt;
            var remaining = this.maxBatchDelayNanos - elapsed;
            if (remaining <= 0) {
                break;
            }

            final AcceptedEvent next;
            try {
                next = this.poller.poll(this.intake, remaining);
            } catch (InterruptedException e) {
                if (this.stopRequested || this.drainRequested) {
                    break;
                }
                continue;
            }

            if (next == null) {
                break;
            }
            batch.add(next);
        }
    }

    private void fillDrainBatch(List<AcceptedEvent> batch) {
        while (batch.size() < this.maxBatchSize) {
            var next = this.intake.poll();
            if (next == null) {
                return;
            }
            batch.add(next);
        }
    }

    private void transitionToFailed(Throwable failure) {
        this.intake.fail(failure);

        synchronized (this.lifecycleMonitor) {
            this.failureCause = failure;
            this.state = State.FAILED;
        }

        try {
            this.failureReporter.report(failure);
        } catch (Throwable reportingFailure) {
            if (reportingFailure != failure) {
                failure.addSuppressed(reportingFailure);
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
        DRAINING,
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
