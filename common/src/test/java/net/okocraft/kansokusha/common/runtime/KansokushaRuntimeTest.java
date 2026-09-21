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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

class KansokushaRuntimeTest {

    private static final Key EVENT_TYPE = Key.key("example", "runtime-test");
    private static final Key PAPER_SERVER = Key.key("example", "paper");
    private static final Key PROXY_SERVER = Key.key("example", "backend");
    private static final EventTypeDefinition DEFINITION = new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    @Test
    void testIndependentRuntimesPersistAndDrainOnClose(@TempDir Path dir) throws Exception {
        var paperDir = dir.resolve("paper");
        var proxyDir = dir.resolve("proxy");
        writeConfig(paperDir);
        writeConfig(proxyDir);

        var failures = new CopyOnWriteArrayList<Throwable>();
        var paper = KansokushaRuntime.start(paperDir, PAPER_SERVER, (message, failure) -> failures.add(failure));
        var proxy = KansokushaRuntime.start(proxyDir, (message, failure) -> failures.add(failure));

        try (paper; proxy) {
            Assertions.assertEquals(KansokushaRuntime.State.RUNNING, paper.state());
            Assertions.assertEquals(Optional.empty(), paper.failureCause());
            Assertions.assertEquals(Optional.of(PAPER_SERVER), paper.api().localServerKey());
            Assertions.assertEquals(Optional.empty(), proxy.api().localServerKey());

            Assertions.assertEquals(RegistrationOutcome.REGISTERED, paper.api().registerEventType(DEFINITION));
            Assertions.assertEquals(RegistrationOutcome.REGISTERED, proxy.api().registerEventType(DEFINITION));
            Assertions.assertEquals(SubmissionOutcome.ACCEPTED, paper.api().submit(submission(PAPER_SERVER, 1)));
            Assertions.assertEquals(SubmissionOutcome.ACCEPTED, proxy.api().submit(submission(PROXY_SERVER, 2)));
        }

        Assertions.assertEquals(KansokushaRuntime.State.CLOSED, paper.state());
        Assertions.assertEquals(KansokushaRuntime.State.CLOSED, proxy.state());
        Assertions.assertTrue(failures.isEmpty(), failures::toString);

        var paperDatabase = paperDir.resolve(KansokushaRuntime.DATABASE_FILENAME);
        var proxyDatabase = proxyDir.resolve(KansokushaRuntime.DATABASE_FILENAME);
        Assertions.assertEquals(1, eventCount(paperDatabase));
        Assertions.assertEquals(1, eventCount(proxyDatabase));
    }

    @Test
    void testStartupFailureIsReportedAndDatabaseCanBeReopened(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        var databasePath = dir.resolve(KansokushaRuntime.DATABASE_FILENAME);
        createInvalidMigrationHistory(databasePath);

        var reported = new AtomicReference<Throwable>();

        var failure = Assertions.assertThrows(
            SQLException.class,
            () -> KansokushaRuntime.start(dir, PAPER_SERVER, (message, cause) -> reported.set(cause))
        );

        Assertions.assertSame(failure, reported.get());
        Assertions.assertTrue(failure.getMessage().contains("Invalid DuckDB migration history"));

        try (var ignored = open(databasePath)) {
            // Reopening proves startup cleanup released the database resource.
        }
    }

    private static EventSubmission submission(Key serverKey, int payloadByte) {
        return new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            Instant.parse("2026-09-21T00:00:00Z"),
            serverKey,
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{(byte) payloadByte})
        );
    }

    private static int eventCount(Path databasePath) throws Exception {
        try (var connection = open(databasePath);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM events")) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void createInvalidMigrationHistory(Path databasePath) throws Exception {
        try (var connection = open(databasePath);
             var statement = connection.createStatement()) {
            statement.execute(
                """
                    CREATE TABLE schema_migrations (
                        version INTEGER PRIMARY KEY CHECK (version > 0),
                        name VARCHAR NOT NULL,
                        checksum VARCHAR NOT NULL,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
                    )
                    """
            );
            statement.execute(
                """
                    INSERT INTO schema_migrations (version, name, checksum)
                    VALUES (1, 'wrong_name', 'wrong_checksum')
                    """
            );
        }
    }

    private static java.sql.Connection open(Path databasePath) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        return DriverManager.getConnection(
            "jdbc:duckdb:" + databasePath.toAbsolutePath().normalize()
        );
    }

    private static void writeConfig(Path dir) throws Exception {
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
                    - key: example:default
                      duration: P1D
                  fallback-policy: example:default
                  cleanup-interval: PT1H
                  max-rows-per-pass: 100
                """
        );
    }
}
