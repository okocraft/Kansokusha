package net.okocraft.kansokusha.common.measurement;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.api.BoundedEventIntake;
import net.okocraft.kansokusha.common.api.EventIntake;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import net.okocraft.kansokusha.common.event.RetentionPolicySet;
import net.okocraft.kansokusha.common.storage.EventBatchWriter;
import net.okocraft.kansokusha.common.writer.AsyncBatchWriterService;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BoundedBufferMeasurement {

    private static final Key EVENT_TYPE = Key.key("measurement", "bounded-buffer");
    private static final Key SERVER_KEY = Key.key("measurement", "server");
    private static final Key RETENTION_KEY = Key.key("measurement", "retention");

    private BoundedBufferMeasurement() {
    }

    public static void main(String[] args) throws Exception {
        var options = Options.parse(args);
        var policies = RetentionPolicySet.from(
            new KansokushaConfig.RetentionSettings(
                Map.of(RETENTION_KEY, Duration.ofDays(3650)),
                Map.of(),
                RETENTION_KEY
            )
        );
        var intake = new BoundedEventIntake(options.queueCapacity(), policies);
        var blockingWriter = new BlockingWriter();
        var writerFailure = new AtomicReference<Throwable>();
        var writer = new AsyncBatchWriterService(
            intake,
            blockingWriter,
            writerFailure::set,
            options.batchSize(),
            Duration.ofMillis(1)
        );
        var submission = new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            Instant.parse("2026-09-22T00:00:00Z"),
            SERVER_KEY,
            null,
            null,
            null,
            EventPayload.copyOf(new byte[options.payloadSize()])
        );

        long acceptedBeforeHold = 0;
        long unavailableBeforeHold = 0;
        long acceptedWhileHeld = 0;
        long unavailableWhileHeld = 0;
        var maxQueuedEvents = new AtomicInteger();

        writer.start();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!blockingWriter.hasEntered()) {
                var admission = intake.accept(submission);
                if (admission == EventIntake.Admission.ACCEPTED) {
                    acceptedBeforeHold++;
                } else if (admission == EventIntake.Admission.UNAVAILABLE) {
                    unavailableBeforeHold++;
                    Thread.onSpinWait();
                } else {
                    throw new IllegalStateException(
                        "Intake closed before the writer was held."
                    );
                }
                maxQueuedEvents.accumulateAndGet(intake.size(), Math::max);

                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                        "Writer did not enter the held append within 5 seconds."
                    );
                }
            }

            while (intake.size() < options.queueCapacity()) {
                var admission = intake.accept(submission);
                if (admission == EventIntake.Admission.ACCEPTED) {
                    acceptedWhileHeld++;
                } else {
                    throw new IllegalStateException(
                        "Held writer rejected before the queue reached capacity: "
                            + admission
                    );
                }
                maxQueuedEvents.accumulateAndGet(intake.size(), Math::max);
            }

            for (int attempt = 0; attempt < options.extraAttempts(); attempt++) {
                var admission = intake.accept(submission);
                if (admission == EventIntake.Admission.UNAVAILABLE) {
                    unavailableWhileHeld++;
                } else {
                    throw new IllegalStateException(
                        "Expected unavailable admission beyond queue capacity, got "
                            + admission
                    );
                }
                maxQueuedEvents.accumulateAndGet(intake.size(), Math::max);
            }

            if (maxQueuedEvents.get() > options.queueCapacity()) {
                throw new IllegalStateException(
                    "Observed queue size exceeded configured capacity."
                );
            }
            if (blockingWriter.heldBatchSize() > options.batchSize()) {
                throw new IllegalStateException(
                    "Observed writer batch exceeded configured maximum."
                );
            }

            print("queue_capacity", options.queueCapacity());
            print("max_batch_size", options.batchSize());
            print("payload_bytes", options.payloadSize());
            print("extra_attempts", options.extraAttempts());
            print("held_writer_batch_events", blockingWriter.heldBatchSize());
            print("accepted_before_writer_hold", acceptedBeforeHold);
            print("temporarily_unavailable_before_writer_hold", unavailableBeforeHold);
            print("accepted_while_writer_held", acceptedWhileHeld);
            print("temporarily_unavailable_while_writer_held", unavailableWhileHeld);
            print("max_observed_queued_events", maxQueuedEvents.get());
            print("final_held_queue_events", intake.size());
        } finally {
            blockingWriter.release();
            writer.drainAndStop();
        }

        var failure = writerFailure.get();
        if (failure != null) {
            throw new IllegalStateException(
                "Writer failed during bounded-buffer measurement.",
                failure
            );
        }
    }

    private static void print(String key, Object value) {
        System.out.println(key + "=" + value);
    }

    private static final class BlockingWriter implements EventBatchWriter {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger heldBatchSize = new AtomicInteger();

        @Override
        public int append(List<AcceptedEvent> events) throws SQLException {
            if (this.entered.getCount() > 0) {
                this.heldBatchSize.set(events.size());
                this.entered.countDown();
                try {
                    this.release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while holding writer.", e);
                }
            }
            return events.size();
        }

        private boolean hasEntered() {
            return this.entered.getCount() == 0;
        }

        private int heldBatchSize() {
            return this.heldBatchSize.get();
        }

        private void release() {
            this.release.countDown();
        }
    }

    private record Options(
        int queueCapacity,
        int batchSize,
        int payloadSize,
        int extraAttempts
    ) {

        private static Options parse(String[] args) {
            var values = new java.util.HashMap<String, String>();
            for (var arg : args) {
                var separator = arg.indexOf('=');
                if (!arg.startsWith("--") || separator < 3) {
                    throw new IllegalArgumentException(
                        "Expected --name=value argument: " + arg
                    );
                }
                values.put(arg.substring(2, separator), arg.substring(separator + 1));
            }

            return new Options(
                positiveInt(values, "queue-capacity"),
                positiveInt(values, "batch-size"),
                nonNegativeInt(values, "payload-size"),
                positiveInt(values, "extra-attempts")
            );
        }

        private static int positiveInt(Map<String, String> values, String key) {
            var value = Integer.parseInt(required(values, key));
            if (value <= 0) {
                throw new IllegalArgumentException(key + " must be positive.");
            }
            return value;
        }

        private static int nonNegativeInt(Map<String, String> values, String key) {
            var value = Integer.parseInt(required(values, key));
            if (value < 0) {
                throw new IllegalArgumentException(key + " must not be negative.");
            }
            return value;
        }

        private static String required(Map<String, String> values, String key) {
            var value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "Missing --" + key + "=value argument."
                );
            }
            return value;
        }
    }
}
