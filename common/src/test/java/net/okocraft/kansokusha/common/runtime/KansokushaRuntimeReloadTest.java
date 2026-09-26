package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

class KansokushaRuntimeReloadTest {

    private static final Key EVENT_TYPE = Key.key("example", "reload-test");
    private static final Key SERVER = Key.key("example", "paper");
    private static final Key POLICY = Key.key("example", "retention");
    private static final Key AUDIT_POLICY = Key.key("example", "audit");
    private static final Key QUALIFIER = Key.key("example", "natural");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void testReloadAffectsFutureAdmissionsAndInvalidReloadKeepsActivePolicies(
        @TempDir Path dir
    ) throws Exception {
        writeConfig(dir, "P100D", POLICY.asString());
        var failures = new CopyOnWriteArrayList<Throwable>();

        try (
            var runtime = KansokushaRuntime.start(
                dir,
                SERVER,
                (message, failure) -> failures.add(failure)
            )
        ) {
            Assertions.assertEquals(
                RegistrationOutcome.REGISTERED,
                runtime.api().registerEventType(
                    new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST)
                )
            );
            Assertions.assertEquals(
                SubmissionOutcome.ACCEPTED,
                runtime.api().submit(submission(1))
            );

            writeConfig(dir, "P200D", POLICY.asString());
            runtime.reloadRetentionPolicies();
            Assertions.assertEquals(
                SubmissionOutcome.ACCEPTED,
                runtime.api().submit(submission(2))
            );

            writeConfig(dir, "P300D", "example:missing");
            Assertions.assertThrows(
                IOException.class,
                runtime::reloadRetentionPolicies
            );
            Assertions.assertEquals(
                SubmissionOutcome.ACCEPTED,
                runtime.api().submit(submission(3))
            );
        }

        Assertions.assertTrue(failures.isEmpty(), failures::toString);
        var expiries = persistedExpiries(
            dir.resolve(KansokushaRuntime.DATABASE_FILENAME)
        );
        Assertions.assertEquals(3, expiries.size());
        Assertions.assertEquals(
            OCCURRED_AT.plus(Duration.ofDays(100)).toEpochMilli(),
            expiries.get("01")
        );
        Assertions.assertEquals(
            OCCURRED_AT.plus(Duration.ofDays(200)).toEpochMilli(),
            expiries.get("02")
        );
        Assertions.assertEquals(
            OCCURRED_AT.plus(Duration.ofDays(200)).toEpochMilli(),
            expiries.get("03")
        );
    }

    @Test
    void testReloadReplacesQualifiedMappingsForFutureAdmissions(
        @TempDir Path dir
    ) throws Exception {
        writeQualifiedConfig(dir, "P7D", "P180D", "example:retention");
        var failures = new CopyOnWriteArrayList<Throwable>();

        try (
            var runtime = KansokushaRuntime.start(
                dir,
                SERVER,
                (message, failure) -> failures.add(failure)
            )
        ) {
            Assertions.assertEquals(
                RegistrationOutcome.REGISTERED,
                runtime.api().registerEventType(
                    new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST)
                )
            );
            Assertions.assertEquals(
                SubmissionOutcome.ACCEPTED,
                runtime.api().submit(qualifiedSubmission(1))
            );

            writeQualifiedConfig(dir, "P14D", "P180D", "example:retention");
            runtime.reloadRetentionPolicies();
            Assertions.assertEquals(
                SubmissionOutcome.ACCEPTED,
                runtime.api().submit(qualifiedSubmission(2))
            );
        }

        Assertions.assertTrue(failures.isEmpty(), failures::toString);
        var expiries = persistedExpiries(
            dir.resolve(KansokushaRuntime.DATABASE_FILENAME)
        );
        Assertions.assertEquals(2, expiries.size());
        Assertions.assertEquals(
            OCCURRED_AT.plus(Duration.ofDays(7)).toEpochMilli(),
            expiries.get("01")
        );
        Assertions.assertEquals(
            OCCURRED_AT.plus(Duration.ofDays(14)).toEpochMilli(),
            expiries.get("02")
        );
    }

    private static EventSubmission qualifiedSubmission(int payloadByte) {
        return new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            OCCURRED_AT,
            SERVER,
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{(byte) payloadByte})
                .withRetentionQualifier(QUALIFIER)
        );
    }

    private static EventSubmission submission(int payloadByte) {
        return new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            OCCURRED_AT,
            SERVER,
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{(byte) payloadByte})
        );
    }

    private static Map<String, Long> persistedExpiries(Path databasePath)
        throws Exception {
        var result = new HashMap<String, Long>();
        Class.forName("org.duckdb.DuckDBDriver");

        try (
            var connection = DriverManager.getConnection(
                "jdbc:duckdb:" + databasePath.toAbsolutePath().normalize()
            );
            var statement = connection.prepareStatement(
                """
                    SELECT hex(e.payload) AS payload_hex,
                           epoch_ms(e.expires_at) AS expires_ms
                    FROM events e
                    JOIN payload_generations pg ON pg.id = e.payload_generation_id
                    JOIN event_types et ON et.id = pg.event_type_id
                    WHERE et.event_type_key = ?
                    """
            )
        ) {
            statement.setString(1, EVENT_TYPE.asString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(
                        rows.getString("payload_hex"),
                        rows.getLong("expires_ms")
                    );
                }
            }
        }

        return result;
    }

    private static void writeQualifiedConfig(
        Path dir,
        String shortDuration,
        String auditDuration,
        String fallbackPolicy
    ) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("config.yml"),
            """
                ingestion:
                  queue-capacity: 4
                  max-batch-size: 4
                  max-batch-delay: PT1H
                retention:
                  policies:
                    - key: example:retention
                      duration: %s
                    - key: example:audit
                      duration: %s
                  event-type-mappings:
                    - event-type: example:reload-test
                      policy: example:audit
                    - event-type: example:reload-test
                      qualifier: example:natural
                      policy: example:retention
                  fallback-policy: %s
                  cleanup-interval: PT1H
                  max-rows-per-pass: 100
                """.formatted(shortDuration, auditDuration, fallbackPolicy)
        );
    }

    private static void writeConfig(
        Path dir,
        String retentionDuration,
        String fallbackPolicy
    ) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("config.yml"),
            """
                ingestion:
                  queue-capacity: 4
                  max-batch-size: 4
                  max-batch-delay: PT1H
                retention:
                  policies:
                    - key: example:retention
                      duration: %s
                  fallback-policy: %s
                  cleanup-interval: PT1H
                  max-rows-per-pass: 100
                """.formatted(retentionDuration, fallbackPolicy)
        );
    }
}
