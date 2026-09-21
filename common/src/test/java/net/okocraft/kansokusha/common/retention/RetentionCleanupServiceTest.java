package net.okocraft.kansokusha.common.retention;

import net.okocraft.kansokusha.common.storage.RetentionCleaner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RetentionCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    @Test
    void testStartSchedulesImmediateFixedDelayPass() throws Exception {
        var executor = executor();
        var failure = new AtomicReference<Throwable>();
        var cutoff = new AtomicReference<Instant>();
        var maxRows = new AtomicInteger();
        var service = service((instant, bound) -> {
            cutoff.set(instant);
            maxRows.set(bound);
            return 0;
        }, failure::set, INTERVAL, 37, executor);

        var task = scheduledTask(service, executor, INTERVAL);
        Assertions.assertNull(cutoff.get());

        task.run();

        Assertions.assertEquals(NOW, cutoff.get());
        Assertions.assertEquals(37, maxRows.get());
        Assertions.assertNull(failure.get());

        service.close();
        Mockito.verify(executor).shutdown();
        Mockito.verify(executor).awaitTermination(Mockito.anyLong(), Mockito.eq(TimeUnit.DAYS));
    }
    @Test
    void testCleanupFailuresAreReportedAndNextPassStillRuns() throws Exception {
        for (var expected : List.<Throwable>of(
            new SQLException("cleanup failed"),
            new IllegalStateException("cleanup runtime failure"),
            new AssertionError("cleanup assertion failure")
        )) {
            var executor = executor();
            var reports = new CopyOnWriteArrayList<Throwable>();
            var calls = new AtomicInteger();
            RetentionCleaner cleaner = (cutoff, bound) -> {
                if (calls.incrementAndGet() != 1) {
                    return 0;
                }
                if (expected instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (expected instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (AssertionError) expected;
            };
            var service = service(cleaner, reports::add, INTERVAL, 5, executor);
            var task = scheduledTask(service, executor, INTERVAL);

            task.run();
            task.run();

            Assertions.assertEquals(List.of(expected), reports);
            Assertions.assertEquals(2, calls.get());
            Assertions.assertEquals(RetentionCleanupService.State.RUNNING, service.state());
            service.close();
        }
    }
    @Test
    void testVirtualMachineErrorStopsSchedulingAndIsRethrown() throws Exception {
        var executor = executor();
        var reported = new AtomicReference<Throwable>();
        var failure = new OutOfMemoryError("fatal cleanup failure");
        var service = service((cutoff, bound) -> {
            throw failure;
        }, reported::set, INTERVAL, 5, executor);
        var task = scheduledTask(service, executor, INTERVAL);

        Assertions.assertSame(failure, Assertions.assertThrows(OutOfMemoryError.class, task::run));
        Assertions.assertSame(failure, reported.get());
        Assertions.assertEquals(RetentionCleanupService.State.FAILED, service.state());
        Mockito.verify(executor).shutdown();

        service.close();
        Assertions.assertEquals(RetentionCleanupService.State.FAILED, service.state());
    }
    @Test
    void testCloseFromCleanupReporterDoesNotAwaitOwnTermination() throws Exception {
        var executor = executor();
        var serviceRef = new AtomicReference<RetentionCleanupService>();
        var reported = new AtomicReference<Throwable>();
        var failure = new SQLException("cleanup failed");
        var service = service(
            (cutoff, bound) -> {
                throw failure;
            },
            cause -> {
                reported.set(cause);
                serviceRef.get().close();
            },
            INTERVAL,
            5,
            executor
        );
        serviceRef.set(service);

        scheduledTask(service, executor, INTERVAL).run();

        Assertions.assertSame(failure, reported.get());
        Assertions.assertEquals(RetentionCleanupService.State.STOPPED, service.state());
        Mockito.verify(executor).shutdown();
        Mockito.verify(executor, Mockito.never())
            .awaitTermination(Mockito.anyLong(), Mockito.any(TimeUnit.class));
    }
    @Test
    void testInvalidSettingsAreRejected() {
        RetentionCleaner cleaner = (cutoff, bound) -> 0;
        var executor = Mockito.mock(ScheduledExecutorService.class);

        Assertions.assertThrows(IllegalArgumentException.class, () ->
            service(cleaner, failure -> { }, Duration.ZERO, 1, executor)
        );
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            service(cleaner, failure -> { }, Duration.ofNanos(1), 1, executor)
        );
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            service(cleaner, failure -> { }, INTERVAL, 0, executor)
        );
    }
    private static ScheduledExecutorService executor() throws InterruptedException {
        var executor = Mockito.mock(ScheduledExecutorService.class);
        Mockito.when(executor.awaitTermination(Mockito.anyLong(), Mockito.any(TimeUnit.class))).thenReturn(true);
        return executor;
    }
    private static Runnable scheduledTask(
        RetentionCleanupService service, ScheduledExecutorService executor, Duration interval
    ) {
        var task = ArgumentCaptor.forClass(Runnable.class);
        service.start();
        Mockito.verify(executor).scheduleWithFixedDelay(
            task.capture(), Mockito.eq(0L), Mockito.eq(interval.toMillis()), Mockito.eq(TimeUnit.MILLISECONDS)
        );
        return task.getValue();
    }
    private static RetentionCleanupService service(
        RetentionCleaner cleaner, RetentionCleanupService.CleanupFailureReporter reporter,
        Duration interval, int maxRowsPerPass, ScheduledExecutorService executor
    ) {
        return new RetentionCleanupService(
            cleaner, reporter, interval, maxRowsPerPass, Clock.fixed(NOW, ZoneOffset.UTC), executor
        );
    }
}
