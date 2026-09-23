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
            (source, timeoutNanos) -> source.awaitNext(timeoutNanos, TimeUnit.NANOSECONDS)
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
        this.intake = Objects.requireNonNull(intake, "intake");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.maxBatchSize = maxBatchSize;
        this.maxBatchDelayNanos = maxBatchDelay.toNanos();
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

    public void beginDraining() {
        this.intake.beginDraining();

        synchronized (this.lifecycleMonitor) {
            switch (this.state) {
                case NEW -> {
                    this.state = State.DRAINING;
                    this.worker = this.newWorker();
                    this.worker.start();
                }
                case RUNNING -> this.state = State.DRAINING;
                case DRAINING, STOPPED, FAILED -> {
                }
            }
        }
    }

    public void drainAndStop() {
        this.beginDraining();

        Thread threadToJoin;
        synchronized (this.lifecycleMonitor) {
            threadToJoin = this.worker;
        }

        var interrupted = joinUninterruptibly(threadToJoin);
        this.intake.close();

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        this.drainAndStop();
    }

    private boolean isDrainRequested() {
        return this.intake.state() != BoundedEventIntake.State.RUNNING;
    }

    static boolean joinUninterruptibly(@Nullable Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return false;
        }

        var interrupted = false;
        while (true) {
            try {
                thread.join();
                return interrupted;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
    }

    public State state() {
        return this.state;
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

                var batch = new ArrayList<AcceptedEvent>();
                batch.add(first);

                if (this.isDrainRequested()) {
                    this.fillDrainBatch(batch);
                } else {
                    this.fillTimedBatch(batch);
                    if (this.isDrainRequested()) {
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
            if (this.isDrainRequested()) {
                return this.intake.poll();
            }

            try {
                var event = this.intake.awaitNext();
                if (event != null) {
                    return event;
                }
            } catch (InterruptedException ignored) {
                // Keep waiting until an event arrives or draining begins.
            }
        }
    }

    private void fillTimedBatch(List<AcceptedEvent> batch) {
        var batchStartedAt = this.clock.nanoTime();

        while (batch.size() < this.maxBatchSize && !this.isDrainRequested()) {
            var elapsed = this.clock.nanoTime() - batchStartedAt;
            var remaining = this.maxBatchDelayNanos - elapsed;
            if (remaining <= 0) {
                break;
            }

            final AcceptedEvent next;
            try {
                next = this.poller.poll(this.intake, remaining);
            } catch (InterruptedException e) {
                if (this.isDrainRequested()) {
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
        this.intake.fail();

        synchronized (this.lifecycleMonitor) {
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
