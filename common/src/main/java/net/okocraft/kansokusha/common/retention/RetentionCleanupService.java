package net.okocraft.kansokusha.common.retention;

import net.okocraft.kansokusha.common.storage.RetentionCleaner;
import net.okocraft.kansokusha.common.writer.PipelineFailureReporter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
@NotNullByDefault
public final class RetentionCleanupService implements AutoCloseable {

    private final RetentionCleaner cleaner;
    private final PipelineFailureReporter failureReporter;
    private static final long MAX_WAIT_CHUNK_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final long intervalMillis;
    private final int maxRowsPerPass;
    private final Clock clock;
    private final Object lifecycleMonitor = new Object();

    private volatile State state = State.NEW;
    private boolean stopRequested;
    @Nullable
    private volatile Throwable failureCause;
    @Nullable
    private Thread worker;

    public RetentionCleanupService(
        RetentionCleaner cleaner,
        PipelineFailureReporter failureReporter,
        Duration interval,
        int maxRowsPerPass
    ) {
        this(cleaner, failureReporter, interval, maxRowsPerPass, Clock.systemUTC());
    }

    RetentionCleanupService(
        RetentionCleaner cleaner,
        PipelineFailureReporter failureReporter,
        Duration interval,
        int maxRowsPerPass,
        Clock clock
    ) {
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive.");
        }
        if (interval.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("interval must use whole milliseconds.");
        }
        try {
            this.intervalMillis = interval.toMillis();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("interval exceeds the supported millisecond range.", e);
        }
        if (maxRowsPerPass <= 0) {
            throw new IllegalArgumentException("maxRowsPerPass must be positive.");
        }
        this.maxRowsPerPass = maxRowsPerPass;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start() {
        synchronized (this.lifecycleMonitor) {
            if (this.state != State.NEW) {
                throw new IllegalStateException("Retention cleanup service can only be started once.");
            }

            this.worker = Thread.ofPlatform()
                .name("kansokusha-retention-cleanup")
                .unstarted(this::runLoop);
            this.state = State.RUNNING;
            this.worker.start();
        }
    }

    @Override
    public void close() {
        final Thread threadToJoin;
        synchronized (this.lifecycleMonitor) {
            if (this.state == State.NEW) {
                this.stopRequested = true;
                this.state = State.STOPPED;
                return;
            }
            if (this.state == State.STOPPED || this.state == State.FAILED) {
                return;
            }

            this.stopRequested = true;
            this.state = State.STOPPING;
            this.lifecycleMonitor.notifyAll();
            threadToJoin = this.worker;
        }

        var interrupted = joinUninterruptibly(threadToJoin);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public State state() {
        return this.state;
    }

    public Optional<Throwable> failureCause() {
        return Optional.ofNullable(this.failureCause);
    }

    private void runLoop() {
        Throwable fatalFailure = null;

        try {
            while (this.beginPass()) {
                try {
                    this.cleaner.deleteExpired(this.clock.instant(), this.maxRowsPerPass);
                } catch (SQLException | RuntimeException e) {
                    this.reportFailure(e);
                } catch (Error e) {
                    this.reportFailure(e);
                    fatalFailure = e;
                    throw e;
                }

                if (!this.awaitNextPass()) {
                    break;
                }
            }
        } finally {
            synchronized (this.lifecycleMonitor) {
                if (fatalFailure != null) {
                    this.failureCause = fatalFailure;
                    this.state = State.FAILED;
                } else {
                    this.state = State.STOPPED;
                }
                this.lifecycleMonitor.notifyAll();
            }
        }
    }

    private boolean beginPass() {
        synchronized (this.lifecycleMonitor) {
            if (this.stopRequested) {
                return false;
            }
            return true;
        }
    }

    private boolean awaitNextPass() {
        var remainingMillis = this.intervalMillis;

        while (remainingMillis > 0) {
            var chunkMillis = Math.min(remainingMillis, MAX_WAIT_CHUNK_MILLIS);
            var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(chunkMillis);

            synchronized (this.lifecycleMonitor) {
                while (!this.stopRequested) {
                    var remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        break;
                    }

                    try {
                        var millis = remainingNanos / 1_000_000;
                        var nanos = (int) (remainingNanos % 1_000_000);
                        this.lifecycleMonitor.wait(millis, nanos);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (this.stopRequested) {
                    return false;
                }
            }

            remainingMillis -= chunkMillis;
        }

        return true;
    }

    private void reportFailure(Throwable failure) {
        try {
            this.failureReporter.report(failure);
        } catch (Throwable reportingFailure) {
            if (reportingFailure != failure) {
                failure.addSuppressed(reportingFailure);
            }
        }
    }

    private static boolean joinUninterruptibly(@Nullable Thread thread) {
        if (thread == null) {
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

    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }
}
