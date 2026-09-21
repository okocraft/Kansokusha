package net.okocraft.kansokusha.common.retention;

import net.okocraft.kansokusha.common.storage.RetentionCleaner;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
@NotNullByDefault
public final class RetentionCleanupService implements AutoCloseable {

    private final RetentionCleaner cleaner;
    private final CleanupFailureReporter failureReporter;
    private final long intervalMillis;
    private final int maxRowsPerPass;
    private final Clock clock;
    private final Object lifecycleMonitor = new Object();

    private volatile State state = State.NEW;
    @Nullable
    private ScheduledExecutorService executor;

    public RetentionCleanupService(
        RetentionCleaner cleaner,
        CleanupFailureReporter failureReporter,
        Duration interval,
        int maxRowsPerPass
    ) {
        this(cleaner, failureReporter, interval, maxRowsPerPass, Clock.systemUTC());
    }

    RetentionCleanupService(
        RetentionCleaner cleaner,
        CleanupFailureReporter failureReporter,
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

            this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform()
                    .name("kansokusha-retention-cleanup")
                    .factory()
            );
            this.state = State.RUNNING;
            this.executor.scheduleWithFixedDelay(
                this::runPass,
                0,
                this.intervalMillis,
                TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public void close() {
        final ScheduledExecutorService executorToClose;
        synchronized (this.lifecycleMonitor) {
            if (this.state == State.NEW) {
                this.state = State.STOPPED;
                return;
            }
            if (this.state == State.STOPPED) {
                return;
            }

            executorToClose = this.executor;
            this.state = State.STOPPING;
        }

        if (executorToClose != null) {
            executorToClose.shutdown();
            var interrupted = awaitTerminationUninterruptibly(executorToClose);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (this.lifecycleMonitor) {
            this.state = State.STOPPED;
        }
    }

    public State state() {
        return this.state;
    }

    private void runPass() {
        synchronized (this.lifecycleMonitor) {
            if (this.state != State.RUNNING) {
                return;
            }
        }

        try {
            this.cleaner.deleteExpired(this.clock.instant(), this.maxRowsPerPass);
        } catch (SQLException | RuntimeException | Error e) {
            this.reportFailure(e);
        }
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

    private static boolean awaitTerminationUninterruptibly(ScheduledExecutorService executor) {
        var interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    @FunctionalInterface
    public interface CleanupFailureReporter {

        void report(Throwable failure);
    }

    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
