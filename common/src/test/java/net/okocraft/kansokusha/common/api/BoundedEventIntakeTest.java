package net.okocraft.kansokusha.common.api;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.event.RetentionPolicySet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

class BoundedEventIntakeTest {

    private static final Key EVENT_TYPE = Key.key("test", "event");
    private static final Key SERVER = Key.key("test", "server");
    private static final Key POLICY = Key.key("test", "retention");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void testAcceptedEventContainsResolvedRetentionMetadata() {
        var intake = new BoundedEventIntake(2, policies(Duration.ofHours(1)));

        Assertions.assertEquals(
            EventIntake.Admission.ACCEPTED,
            intake.accept(submission(OCCURRED_AT))
        );

        var accepted = intake.poll();
        Assertions.assertNotNull(accepted);
        Assertions.assertEquals(POLICY, accepted.retentionPolicyKey());
        Assertions.assertEquals(
            Instant.parse("2026-01-01T01:00:00Z"),
            accepted.expiresAt()
        );
        Assertions.assertEquals(0, intake.size());
    }

    @Test
    void testCapacityIsStrictlyBoundedAndRecoversAfterPoll() {
        var intake = new BoundedEventIntake(2, policies(Duration.ofHours(1)));

        Assertions.assertEquals(EventIntake.Admission.ACCEPTED, intake.accept(submission(OCCURRED_AT)));
        Assertions.assertEquals(EventIntake.Admission.ACCEPTED, intake.accept(submission(OCCURRED_AT.plusMillis(1))));
        Assertions.assertEquals(
            EventIntake.Admission.UNAVAILABLE,
            intake.accept(submission(OCCURRED_AT.plusMillis(2)))
        );
        Assertions.assertEquals(2, intake.size());
        Assertions.assertEquals(2, intake.capacity());

        Assertions.assertNotNull(intake.poll());
        Assertions.assertEquals(
            EventIntake.Admission.ACCEPTED,
            intake.accept(submission(OCCURRED_AT.plusMillis(3)))
        );
        Assertions.assertEquals(2, intake.size());
    }

    @Test
    void testConcurrentProducersNeverExceedCapacity() throws Exception {
        int capacity = 64;
        int producers = 8;
        int submissionsPerProducer = 100;
        var intake = new BoundedEventIntake(capacity, policies(Duration.ofHours(1)));
        var ready = new CountDownLatch(producers);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        var unavailable = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(producers)) {
            var futures = java.util.stream.IntStream.range(0, producers)
                .mapToObj(producer -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (var index = 0; index < submissionsPerProducer; index++) {
                        var result = intake.accept(
                            submission(OCCURRED_AT.plusMillis((long) producer * submissionsPerProducer + index))
                        );
                        if (result == EventIntake.Admission.ACCEPTED) {
                            accepted.incrementAndGet();
                        } else if (result == EventIntake.Admission.UNAVAILABLE) {
                            unavailable.incrementAndGet();
                        } else {
                            throw new AssertionError("Unexpected admission: " + result);
                        }
                    }
                    return null;
                }))
                .toList();

            ready.await();
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        Assertions.assertEquals(capacity, accepted.get());
        Assertions.assertEquals(
            producers * submissionsPerProducer - capacity,
            unavailable.get()
        );
        Assertions.assertEquals(capacity, intake.size());
    }

    @Test
    void testLifecycleTransitionsStopNewOwnershipTransfer() {
        var intake = new BoundedEventIntake(2, policies(Duration.ofHours(1)));

        Assertions.assertEquals(EventIntake.Admission.ACCEPTED, intake.accept(submission(OCCURRED_AT)));
        intake.beginDraining();

        Assertions.assertEquals(BoundedEventIntake.State.DRAINING, intake.state());
        Assertions.assertEquals(
            EventIntake.Admission.CLOSED,
            intake.accept(submission(OCCURRED_AT.plusMillis(1)))
        );
        Assertions.assertNotNull(intake.poll());

        intake.close();
        Assertions.assertEquals(BoundedEventIntake.State.CLOSED, intake.state());
        Assertions.assertEquals(
            EventIntake.Admission.CLOSED,
            intake.accept(submission(OCCURRED_AT.plusMillis(2)))
        );
    }

    @Test
    void testFailedIntakeIsUnavailableAndDoesNotReopen() {
        var intake = new BoundedEventIntake(1, policies(Duration.ofHours(1)));

        intake.fail();
        Assertions.assertEquals(BoundedEventIntake.State.FAILED, intake.state());
        Assertions.assertEquals(
            EventIntake.Admission.UNAVAILABLE,
            intake.accept(submission(OCCURRED_AT))
        );

        intake.beginDraining();
        Assertions.assertEquals(BoundedEventIntake.State.FAILED, intake.state());

        intake.close();
        Assertions.assertEquals(BoundedEventIntake.State.CLOSED, intake.state());
    }

    @Test
    void testRetentionResolutionFailureRejectsOnlyThatSubmission() {
        var intake = new BoundedEventIntake(1, policies(Duration.ofMillis(1)));

        Assertions.assertEquals(
            EventIntake.Admission.UNAVAILABLE,
            intake.accept(submission(Instant.MAX))
        );
        Assertions.assertEquals(BoundedEventIntake.State.RUNNING, intake.state());
        Assertions.assertEquals(0, intake.size());

        Assertions.assertEquals(
            EventIntake.Admission.ACCEPTED,
            intake.accept(submission(OCCURRED_AT))
        );
    }

    @Test
    void testNonPositiveCapacityIsRejected() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new BoundedEventIntake(0, policies(Duration.ofHours(1)))
        );
    }

    private static RetentionPolicySet policies(Duration duration) {
        return RetentionPolicySet.from(
            new KansokushaConfig.RetentionSettings(
                Map.of(POLICY, duration),
                Map.of(),
                POLICY
            )
        );
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
