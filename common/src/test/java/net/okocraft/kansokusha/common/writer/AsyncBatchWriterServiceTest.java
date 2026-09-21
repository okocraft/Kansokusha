package net.okocraft.kansokusha.common.writer;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.api.BoundedEventIntake;
import net.okocraft.kansokusha.common.api.EventIntake;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import net.okocraft.kansokusha.common.event.RetentionPolicySet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class AsyncBatchWriterServiceTest {

    private static final Key EVENT_TYPE = Key.key("test", "writer-event");
    private static final Key SERVER = Key.key("test", "server");
    private static final Key POLICY = Key.key("test", "retention");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final PipelineFailureReporter NOOP_REPORTER = failure -> {
    };

    @Test
    void testFullBatchesFlushAtConfiguredSize() throws Exception {
        var intake = intake(8);
        submit(intake, 6);
        var batches = new CopyOnWriteArrayList<List<AcceptedEvent>>();
        var written = new CountDownLatch(2);
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                batches.add(List.copyOf(events));
                written.countDown();
                return events.size();
            },
            NOOP_REPORTER,
            3,
            Duration.ofHours(1)
        );

        service.start();

        Assertions.assertTrue(written.await(2, TimeUnit.SECONDS));
        service.requestStop();
        service.awaitStopped();

        Assertions.assertEquals(
            List.of(3, 3),
            batches.stream().map(List::size).toList()
        );
        Assertions.assertEquals(0, intake.size());
        Assertions.assertEquals(AsyncBatchWriterService.State.STOPPED, service.state());
    }

    @Test
    void testPartialBatchFlushesWhenDelayExpiresWithoutResettingTimer() throws Exception {
        var intake = intake(4);
        submit(intake, 2);
        var now = new AtomicLong();
        var pollTimeouts = new CopyOnWriteArrayList<Long>();
        var calls = new AtomicInteger();
        var batches = new CopyOnWriteArrayList<List<AcceptedEvent>>();
        var written = new CountDownLatch(1);
        var delay = Duration.ofMillis(100);

        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                batches.add(List.copyOf(events));
                written.countDown();
                return events.size();
            },
            NOOP_REPORTER,
            4,
            delay,
            now::get,
            (source, timeoutNanos) -> {
                pollTimeouts.add(timeoutNanos);
                if (calls.getAndIncrement() == 0) {
                    var next = source.poll();
                    Assertions.assertNotNull(next);
                    now.addAndGet(Duration.ofMillis(40).toNanos());
                    return next;
                }
                return null;
            }
        );

        service.start();

        Assertions.assertTrue(written.await(2, TimeUnit.SECONDS));
        service.requestStop();
        service.awaitStopped();

        Assertions.assertEquals(1, batches.size());
        Assertions.assertEquals(2, batches.getFirst().size());
        Assertions.assertEquals(
            List.of(Duration.ofMillis(100).toNanos(), Duration.ofMillis(60).toNanos()),
            pollTimeouts
        );
    }

    @Test
    void testIdleWorkerWakesWhenEventArrives() throws Exception {
        var intake = intake(2);
        var written = new CountDownLatch(1);
        var batches = new CopyOnWriteArrayList<List<AcceptedEvent>>();
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                batches.add(List.copyOf(events));
                written.countDown();
                return events.size();
            },
            NOOP_REPORTER,
            1,
            Duration.ofSeconds(1)
        );

        service.start();

        Assertions.assertFalse(written.await(50, TimeUnit.MILLISECONDS));
        submit(intake, 1);
        Assertions.assertTrue(written.await(2, TimeUnit.SECONDS));

        service.requestStop();
        service.awaitStopped();

        Assertions.assertEquals(1, batches.size());
        Assertions.assertEquals(1, batches.getFirst().size());
    }

    @Test
    void testStopInterruptsPartialWaitAndFlushesHeldBatch() throws Exception {
        var intake = intake(2);
        submit(intake, 1);
        var waitingForMore = new CountDownLatch(1);
        var neverReleased = new CountDownLatch(1);
        var batches = new CopyOnWriteArrayList<List<AcceptedEvent>>();
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                batches.add(List.copyOf(events));
                return events.size();
            },
            NOOP_REPORTER,
            2,
            Duration.ofHours(1),
            System::nanoTime,
            (source, timeoutNanos) -> {
                waitingForMore.countDown();
                neverReleased.await();
                return source.poll();
            }
        );

        service.start();
        Assertions.assertTrue(waitingForMore.await(2, TimeUnit.SECONDS));

        service.requestStop();
        service.awaitStopped();

        Assertions.assertEquals(AsyncBatchWriterService.State.STOPPED, service.state());
        Assertions.assertEquals(1, batches.size());
        Assertions.assertEquals(1, batches.getFirst().size());
        Assertions.assertEquals(0, intake.size());
    }

    @Test
    void testStopWakesIdleWorkerWithoutWriting() throws Exception {
        var intake = intake(1);
        var writes = new AtomicInteger();
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                writes.incrementAndGet();
                return events.size();
            },
            NOOP_REPORTER,
            1,
            Duration.ofSeconds(1)
        );

        service.start();
        service.requestStop();
        service.awaitStopped();

        Assertions.assertEquals(AsyncBatchWriterService.State.STOPPED, service.state());
        Assertions.assertEquals(0, writes.get());
    }

    @Test
    void testStorageFailureTransitionsPipelineAndReportsOnce() throws Exception {
        var intake = intake(2);
        submit(intake, 2);
        var failure = new SQLException("injected failure");
        var reportCount = new AtomicInteger();
        var reportedFailure = new AtomicReference<Throwable>();
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                throw failure;
            },
            reported -> {
                reportCount.incrementAndGet();
                reportedFailure.compareAndSet(null, reported);
            },
            1,
            Duration.ofSeconds(1)
        );

        service.start();
        service.awaitStopped();

        Assertions.assertEquals(AsyncBatchWriterService.State.FAILED, service.state());
        Assertions.assertSame(failure, service.failureCause().orElseThrow());
        Assertions.assertEquals(BoundedEventIntake.State.FAILED, intake.state());
        Assertions.assertSame(failure, intake.failureCause().orElseThrow());
        Assertions.assertEquals(1, reportCount.get());
        Assertions.assertSame(failure, reportedFailure.get());
        Assertions.assertEquals(1, intake.size());

        for (var index = 0; index < 100; index++) {
            Assertions.assertEquals(
                EventIntake.Admission.UNAVAILABLE,
                intake.accept(submission(OCCURRED_AT.plusSeconds(index + 1L)))
            );
        }
        Assertions.assertEquals(1, intake.size());
    }

    @Test
    void testReportingFailureDoesNotReplacePipelineFailure() throws Exception {
        var intake = intake(1);
        submit(intake, 1);
        var pipelineFailure = new SQLException("storage failed");
        var reportingFailure = new IllegalStateException("reporting failed");
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                throw pipelineFailure;
            },
            failure -> {
                throw reportingFailure;
            },
            1,
            Duration.ofSeconds(1)
        );

        service.start();
        service.awaitStopped();

        Assertions.assertEquals(AsyncBatchWriterService.State.FAILED, service.state());
        Assertions.assertSame(pipelineFailure, service.failureCause().orElseThrow());
        Assertions.assertSame(pipelineFailure, intake.failureCause().orElseThrow());
        Assertions.assertEquals(List.of(reportingFailure), List.of(pipelineFailure.getSuppressed()));
    }

    @Test
    void testUncheckedWriterFailureTerminatesWorkerAsFailed() throws Exception {
        var intake = intake(1);
        submit(intake, 1);
        var failure = new IllegalStateException("unchecked writer failure");
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                throw failure;
            },
            NOOP_REPORTER,
            1,
            Duration.ofSeconds(1)
        );

        service.start();
        service.awaitStopped();

        Assertions.assertEquals(AsyncBatchWriterService.State.FAILED, service.state());
        Assertions.assertSame(failure, service.failureCause().orElseThrow());
    }

    @Test
    void testErrorFailureIsRecordedBeforeWorkerRethrows() throws Exception {
        var intake = intake(1);
        submit(intake, 1);
        var failure = new AssertionError("fatal writer failure");
        var service = new AsyncBatchWriterService(
            intake,
            events -> {
                throw failure;
            },
            NOOP_REPORTER,
            1,
            Duration.ofSeconds(1)
        );

        service.start();
        service.awaitStopped();

        Assertions.assertEquals(AsyncBatchWriterService.State.FAILED, service.state());
        Assertions.assertSame(failure, service.failureCause().orElseThrow());
    }

    @Test
    void testInvalidBatchSettingsAreRejected() {
        var intake = intake(1);

        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new AsyncBatchWriterService(
                intake,
                events -> events.size(),
                NOOP_REPORTER,
                0,
                Duration.ofSeconds(1)
            )
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new AsyncBatchWriterService(
                intake,
                events -> events.size(),
                NOOP_REPORTER,
                1,
                Duration.ZERO
            )
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new AsyncBatchWriterService(
                intake,
                events -> events.size(),
                NOOP_REPORTER,
                1,
                Duration.ofNanos(1)
            )
        );
    }

    private static BoundedEventIntake intake(int capacity) {
        return new BoundedEventIntake(
            capacity,
            RetentionPolicySet.from(
                new KansokushaConfig.RetentionSettings(
                    Map.of(POLICY, Duration.ofHours(1)),
                    Map.of(),
                    POLICY
                )
            )
        );
    }

    private static void submit(BoundedEventIntake intake, int count) {
        for (var index = 0; index < count; index++) {
            Assertions.assertEquals(
                EventIntake.Admission.ACCEPTED,
                intake.accept(submission(OCCURRED_AT.plusMillis(index)))
            );
        }
    }

    private static EventSubmission submission(Instant occurredAt) {
        return new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            occurredAt,
            SERVER,
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{1})
        );
    }
}
