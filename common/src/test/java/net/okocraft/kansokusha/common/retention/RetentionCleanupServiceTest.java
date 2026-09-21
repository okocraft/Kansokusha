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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RetentionCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void testStartupRunsImmediatePassOnBackgroundThread() throws Exception {
        var caller = Thread.currentThread();
        var pass = new CountDownLatch(1);
        var cleanupThread = new AtomicReference<Thread>();
        var cutoff = new AtomicReference<Instant>();
        var maxRows = new AtomicInteger();
        var service = new RetentionCleanupService(
            (instant, bound) -> {
                cleanupThread.set(Thread.currentThread());
                cutoff.set(instant);
                maxRows.set(bound);
                pass.countDown();
                return 0;
            },
            failure -> Assertions.fail(failure),
            Duration.ofHours(1),
            37,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        service.start();

        Assertions.assertTrue(pass.await(2, TimeUnit.SECONDS));
        service.close();

        Assertions.assertNotSame(caller, cleanupThread.get());
        Assertions.assertEquals(NOW, cutoff.get());
        Assertions.assertEquals(37, maxRows.get());
        Assertions.assertEquals(RetentionCleanupService.State.STOPPED, service.state());
    }

    @Test
    void testFixedDelayStartsAfterPreviousPassCompletes() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service = new RetentionCleanupService(
            (cutoff, bound) -> {
                var call = calls.incrementAndGet();
                if (call == 1) {
                    firstStarted.countDown();
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException e) {
                        throw new SQLException("cleanup pass interrupted", e);
                    }
                } else if (call == 2) {
                    secondStarted.countDown();
                }
                return 0;
            },
            failure -> Assertions.fail(failure),
            Duration.ofMillis(150),
            10
        );

        service.start();
        Assertions.assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        Thread.sleep(200);
        Assertions.assertEquals(1, calls.get());

        releaseFirst.countDown();
        Assertions.assertFalse(secondStarted.await(75, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(secondStarted.await(2, TimeUnit.SECONDS));

        service.close();
        Assertions.assertTrue(calls.get() >= 2);
    }

    @Test
    void testCleanupFailureIsReportedAndNextPassStillRuns() throws Exception {
        var reports = new CopyOnWriteArrayList<Throwable>();
        var secondPass = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var failure = new SQLException("cleanup failed");
        RetentionCleaner cleaner = (cutoff, bound) -> {
            if (calls.incrementAndGet() == 1) {
                throw failure;
            }
            secondPass.countDown();
            return 0;
        };
        var service = new RetentionCleanupService(
            cleaner,
            reports::add,
            Duration.ofMillis(25),
            5
        );

        service.start();

        Assertions.assertTrue(secondPass.await(2, TimeUnit.SECONDS));
        service.close();

        Assertions.assertEquals(List.of(failure), reports);
        Assertions.assertTrue(calls.get() >= 2);
        Assertions.assertEquals(RetentionCleanupService.State.STOPPED, service.state());
    }

    @Test
    void testErrorIsReportedAndNextPassStillRuns() throws Exception {
        var reports = new CopyOnWriteArrayList<Throwable>();
        var secondPass = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var failure = new AssertionError("cleanup error");
        var service = new RetentionCleanupService(
            (cutoff, bound) -> {
                if (calls.incrementAndGet() == 1) {
                    throw failure;
                }
                secondPass.countDown();
                return 0;
            },
            reports::add,
            Duration.ofMillis(25),
            5
        );

        service.start();

        Assertions.assertTrue(secondPass.await(2, TimeUnit.SECONDS));
        service.close();

        Assertions.assertEquals(List.of(failure), reports);
        Assertions.assertTrue(calls.get() >= 2);
        Assertions.assertEquals(RetentionCleanupService.State.STOPPED, service.state());
    }

    @Test
    void testCloseWaitsForRunningPassAndPreventsAnotherPass() throws Exception {
        var passStarted = new CountDownLatch(1);
        var releasePass = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service = new RetentionCleanupService(
            (cutoff, bound) -> {
                calls.incrementAndGet();
                passStarted.countDown();
                try {
                    releasePass.await();
                } catch (InterruptedException e) {
                    throw new SQLException("cleanup pass interrupted", e);
                }
                return 0;
            },
            failure -> Assertions.fail(failure),
            Duration.ofMillis(1),
            5
        );

        service.start();
        Assertions.assertTrue(passStarted.await(2, TimeUnit.SECONDS));

        var shutdown = Thread.ofPlatform().start(service::close);
        shutdown.join(50);
        Assertions.assertTrue(shutdown.isAlive());

        releasePass.countDown();
        shutdown.join(2_000);

        Assertions.assertFalse(shutdown.isAlive());
        Thread.sleep(25);
        Assertions.assertEquals(1, calls.get());
        Assertions.assertEquals(RetentionCleanupService.State.STOPPED, service.state());
    }

    @Test
    void testInvalidSettingsAreRejected() {
        RetentionCleaner cleaner = (cutoff, bound) -> 0;

        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new RetentionCleanupService(cleaner, failure -> {
            }, Duration.ZERO, 1)
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new RetentionCleanupService(cleaner, failure -> {
            }, Duration.ofNanos(1), 1)
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new RetentionCleanupService(cleaner, failure -> {
            }, Duration.ofSeconds(1), 0)
        );
    }
}
