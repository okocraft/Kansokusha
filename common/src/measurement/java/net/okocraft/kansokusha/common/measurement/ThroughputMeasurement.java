package net.okocraft.kansokusha.common.measurement;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ThroughputMeasurement {

    private static final Key EVENT_TYPE = Key.key("measurement", "throughput");
    private static final Key SERVER_KEY = Key.key("measurement", "server");

    private ThroughputMeasurement() {
    }

    public static void main(String[] args) throws Exception {
        var options = Options.parse(args);
        var databasePath = options.dataDirectory().resolve("kansokusha.duckdb");
        if (Files.exists(databasePath)) {
            throw new IllegalArgumentException(
                "Measurement database already exists: " + databasePath
            );
        }

        Files.createDirectories(options.dataDirectory());
        writeConfig(options);

        var reportedFailure = new AtomicReference<Throwable>();
        var runtime = KansokushaRuntime.start(
            options.dataDirectory(),
            (message, failure) -> reportedFailure.compareAndSet(null, failure)
        );

        var api = runtime.api();
        var registration = api.registerEventType(
            new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST)
        );
        if (registration != RegistrationOutcome.REGISTERED) {
            runtime.close();
            throw new IllegalStateException("Event type registration failed: " + registration);
        }

        var payload = new byte[options.payloadSize()];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 31);
        }
        var submission = new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            Instant.now(),
            SERVER_KEY,
            null,
            null,
            null,
            EventPayload.copyOf(payload)
        );

        long attempts = 0;
        long unavailable = 0;
        long accepted = 0;
        var heapPools = heapPools();
        var heapUsedBefore = heapUsedBytes();
        heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
        var cpuStartedAt = processCpuTime();
        var wallStartedAt = System.nanoTime();

        try {
            while (accepted < options.eventCount()) {
                attempts++;
                var outcome = api.submit(submission);
                if (outcome == SubmissionOutcome.ACCEPTED) {
                    accepted++;
                    continue;
                }
                if (outcome == SubmissionOutcome.INGESTION_UNAVAILABLE) {
                    unavailable++;
                    if (runtime.state() == KansokushaRuntime.State.FAILED) {
                        throw new IllegalStateException(
                            "Writer failed during measurement.",
                            runtime.failureCause().orElse(null)
                        );
                    }
                    Thread.onSpinWait();
                    continue;
                }
                throw new IllegalStateException("Unexpected submission outcome: " + outcome);
            }

            var submissionFinishedAt = System.nanoTime();
            runtime.close();
            var persistenceFinishedAt = System.nanoTime();
            var cpuFinishedAt = processCpuTime();
            var peakHeapUsed = peakHeapUsedBytes(heapPools);
            var heapUsedAfter = heapUsedBytes();
            var duckDbDiskBytes = duckDbDiskBytes(databasePath);

            var failure = reportedFailure.get();
            if (failure != null) {
                throw new IllegalStateException("Runtime reported a measurement failure.", failure);
            }

            var persisted = countPersisted(databasePath);
            if (persisted != accepted) {
                throw new IllegalStateException(
                    "Persisted event count " + persisted + " did not match accepted count " + accepted
                );
            }

            var submissionSeconds = seconds(submissionFinishedAt - wallStartedAt);
            var persistenceSeconds = seconds(persistenceFinishedAt - wallStartedAt);
            var processCpuSeconds = seconds(
                cpuFinishedAt.minus(cpuStartedAt).toNanos()
            );
            var logicalPayloadBytes = Math.multiplyExact(
                persisted,
                (long) options.payloadSize()
            );

            printEnvironment(options);
            print("submission_attempts", attempts);
            print("accepted_events", accepted);
            print("temporarily_unavailable_attempts", unavailable);
            print("persisted_events", persisted);
            print("submission_seconds", submissionSeconds);
            print("persistence_seconds", persistenceSeconds);
            print("submitted_events_per_second", accepted / submissionSeconds);
            print("persisted_events_per_second", persisted / persistenceSeconds);
            print("process_cpu_seconds", processCpuSeconds);
            print("heap_used_before_bytes", heapUsedBefore);
            print("peak_heap_used_bytes", peakHeapUsed);
            print("heap_used_after_bytes", heapUsedAfter);
            print("write_volume_proxy", "logical_payload_bytes");
            print("logical_payload_bytes", logicalPayloadBytes);
            print("duckdb_disk_bytes", duckDbDiskBytes);
        } finally {
            if (runtime.state() != KansokushaRuntime.State.CLOSED) {
                runtime.close();
            }
        }
    }

    private static void writeConfig(Options options) throws Exception {
        Files.writeString(
            options.dataDirectory().resolve("config.yml"),
            """
                ingestion:
                  queue-capacity: %d
                  max-batch-size: %d
                  max-batch-delay: %s
                retention:
                  policies:
                    - key: measurement:retention
                      duration: P3650D
                  fallback-policy: measurement:retention
                  cleanup-interval: P1D
                  max-rows-per-pass: 1000
                """.formatted(
                    options.queueCapacity(),
                    options.batchSize(),
                    Duration.ofMillis(options.batchDelayMillis())
                )
        );
    }

    private static long countPersisted(Path databasePath) throws Exception {
        try (
            var connection = DriverManager.getConnection(
                "jdbc:duckdb:" + databasePath.toAbsolutePath()
            );
            var statement = connection.prepareStatement(
                """
                    SELECT count(*)
                    FROM events e
                    JOIN payload_generations pg ON pg.id = e.payload_generation_id
                    JOIN event_types et ON et.id = pg.event_type_id
                    WHERE et.event_type_key = ?
                    """
            )
        ) {
            statement.setString(1, EVENT_TYPE.asString());
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .toList();
    }

    private static long heapUsedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long peakHeapUsedBytes(List<MemoryPoolMXBean> heapPools) {
        return heapPools.stream()
            .map(MemoryPoolMXBean::getPeakUsage)
            .mapToLong(usage -> Math.max(usage.getUsed(), 0))
            .sum();
    }

    private static long duckDbDiskBytes(Path databasePath) throws Exception {
        long bytes = 0;
        var directory = databasePath.getParent();
        var prefix = databasePath.getFileName().toString();

        try (var paths = Files.newDirectoryStream(directory, prefix + "*")) {
            for (var path : paths) {
                if (Files.isRegularFile(path)) {
                    bytes = Math.addExact(bytes, Files.size(path));
                }
            }
        }
        return bytes;
    }

    private static Duration processCpuTime() {
        return ProcessHandle.current().info().totalCpuDuration()
            .orElseThrow(() -> new IllegalStateException("Process CPU time is unavailable."));
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static void printEnvironment(Options options) {
        print("java_version", System.getProperty("java.version"));
        print("java_vm", System.getProperty("java.vm.name"));
        print("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        print("available_processors", Runtime.getRuntime().availableProcessors());
        print("event_count", options.eventCount());
        print("payload_bytes", options.payloadSize());
        print("queue_capacity", options.queueCapacity());
        print("max_batch_size", options.batchSize());
        print("max_batch_delay_ms", options.batchDelayMillis());
        print("data_directory", options.dataDirectory().toAbsolutePath());
    }

    private static void print(String key, Object value) {
        System.out.println(key + "=" + value);
    }

    private record Options(
        long eventCount,
        int payloadSize,
        int queueCapacity,
        int batchSize,
        long batchDelayMillis,
        Path dataDirectory
    ) {

        private static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (var arg : args) {
                var separator = arg.indexOf('=');
                if (!arg.startsWith("--") || separator < 3) {
                    throw new IllegalArgumentException("Expected --name=value argument: " + arg);
                }
                values.put(arg.substring(2, separator), arg.substring(separator + 1));
            }

            var options = new Options(
                positiveLong(values, "event-count"),
                nonNegativeInt(values, "payload-size"),
                positiveInt(values, "queue-capacity"),
                positiveInt(values, "batch-size"),
                positiveLong(values, "batch-delay-ms"),
                Path.of(required(values, "data-dir"))
            );
            if (options.batchDelayMillis() > Long.MAX_VALUE / 1_000_000) {
                throw new IllegalArgumentException("batch-delay-ms is too large.");
            }
            return options;
        }

        private static long positiveLong(Map<String, String> values, String key) {
            var value = Long.parseLong(required(values, key));
            if (value <= 0) {
                throw new IllegalArgumentException(key + " must be positive.");
            }
            return value;
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
                throw new IllegalArgumentException("Missing --" + key + "=value argument.");
            }
            return value;
        }
    }
}
