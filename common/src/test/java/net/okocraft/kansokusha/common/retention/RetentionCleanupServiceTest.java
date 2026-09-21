package net.okocraft.kansokusha.common.retention;

import net.okocraft.kansokusha.common.storage.RetentionCleaner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RetentionCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void testStartSchedulesImmediateFixedDelayPass() {
        var scheduler = new TestScheduler();
        var failure = new AtomicReference<Throwable>();
        var cutoff = new AtomicReference<Instant>();
        var maxRows = new AtomicInteger();
        var service = service(
            (instant, bound) -> {
                cutoff.set(instant);
                maxRows.set(bound);
                return 0;
            },
            failure::set,
            Duration.ofMinutes(5),
            37,
            scheduler
        );

        service.start();

        Assertions.assertEquals(0, scheduler.initialDelayMillis);
        Assertions.assertEquals(Duration.ofMinutes(5).toMillis(), scheduler.delayMillis);
        Assertions.assertNull(cutoff.get());

        scheduler.runPass();

        Assertions.assertEquals(NOW, cutoff.get());
        Assertions.assertEquals(37, maxRows.get());
        Assertions.assertNull(failure.get());

        service.close();
    }

    @Test
    void testCleanupFailuresAreReportedAndNextPassStillRuns() {
        for (var expected : List.<Throwable>of(
            new SQLException("cleanup failed"),
            new AssertionError("cleanup error")
        )) {
            var scheduler = new TestScheduler();
            var reports = new CopyOnWriteArrayList<Throwable>();
            var calls = new AtomicInteger();
            RetentionCleaner cleaner = (cutoff, bound) -> {
                if (calls.incrementAndGet() == 1) {
                    if (expected instanceof SQLException sqlException) {
                        throw sqlException;
                    }
                    throw (Error) expected;
                }
                return 0;
            };
            var service = service(cleaner, reports::add, Duration.ofMinutes(1), 5, scheduler);

            service.start();
            scheduler.runPass();
            scheduler.runPass();

            Assertions.assertEquals(List.of(expected), reports);
            Assertions.assertEquals(2, calls.get());
            Assertions.assertEquals(RetentionCleanupService.State.RUNNING, service.state());

            service.close();
        }
    }

    @Test
    void testVirtualMachineErrorStopsSchedulingAndIsRethrown() {
        var scheduler = new TestScheduler();
        var reported = new AtomicReference<Throwable>();
        var failure = new OutOfMemoryError("fatal cleanup failure");
        var service = service(
            (cutoff, bound) -> {
                throw failure;
            },
            reported::set,
            Duration.ofMinutes(1),
            5,
            scheduler
        );

        service.start();

        var thrown = Assertions.assertThrows(OutOfMemoryError.class, scheduler::runPass);

        Assertions.assertSame(failure, thrown);
        Assertions.assertNull(reported.get());
        Assertions.assertTrue(scheduler.shutdown);
        Assertions.assertEquals(RetentionCleanupService.State.FAILED, service.state());

        service.close();
    }

    @Test
    void testCloseFromCleanupReporterDoesNotAwaitOwnTermination() {
        var scheduler = new TestScheduler();
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
            Duration.ofMinutes(1),
            5,
            scheduler
        );
        serviceRef.set(service);

        service.start();
        scheduler.runPass();

        Assertions.assertSame(failure, reported.get());
        Assertions.assertTrue(scheduler.shutdown);
        Assertions.assertEquals(0, scheduler.awaitTerminationCalls);
        Assertions.assertEquals(RetentionCleanupService.State.STOPPED, service.state());
    }

    @Test
    void testCloseStopsSchedulerAndAwaitsTermination() {
        var scheduler = new TestScheduler();
        var failure = new AtomicReference<Throwable>();
        var service = service(
            (cutoff, bound) -> 0,
            failure::set,
            Duration.ofMinutes(1),
            5,
            scheduler
        );

        service.start();
        service.close();

        Assertions.assertTrue(scheduler.shutdown);
        Assertions.assertEquals(1, scheduler.awaitTerminationCalls);
        Assertions.assertEquals(RetentionCleanupService.State.STOPPED, service.state());
        Assertions.assertNull(failure.get());
    }

    @Test
    void testInvalidSettingsAreRejected() {
        RetentionCleaner cleaner = (cutoff, bound) -> 0;
        var scheduler = new TestScheduler();

        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service(cleaner, failure -> {
            }, Duration.ZERO, 1, scheduler)
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service(cleaner, failure -> {
            }, Duration.ofNanos(1), 1, scheduler)
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service(cleaner, failure -> {
            }, Duration.ofSeconds(1), 0, scheduler)
        );
    }

    private static RetentionCleanupService service(
        RetentionCleaner cleaner,
        RetentionCleanupService.CleanupFailureReporter reporter,
        Duration interval,
        int maxRowsPerPass,
        RetentionCleanupService.CleanupScheduler scheduler
    ) {
        return new RetentionCleanupService(
            cleaner,
            reporter,
            interval,
            maxRowsPerPass,
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler
        );
    }

    private static final class TestScheduler implements RetentionCleanupService.CleanupScheduler {

        private Runnable task;
        private long initialDelayMillis = -1;
        private long delayMillis = -1;
        private boolean shutdown;
        private int awaitTerminationCalls;

        @Override
        public void scheduleWithFixedDelay(Runnable task, long initialDelayMillis, long delayMillis) {
            this.task = task;
            this.initialDelayMillis = initialDelayMillis;
            this.delayMillis = delayMillis;
        }

        @Override
        public void shutdown() {
            this.shutdown = true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            this.awaitTerminationCalls++;
            return true;
        }

        private void runPass() {
            Assertions.assertNotNull(this.task);
            this.task.run();
        }
    }
}
