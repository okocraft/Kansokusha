package net.okocraft.kansokusha.testplugin;

import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class ExternalPaperPlugin extends JavaPlugin {

    private static final NamespacedKey EVENT_TYPE =
        new NamespacedKey("fixture", "custom_event");

    private Path resultFile;
    private Path databaseFile;

    @Override
    public void onEnable() {
        this.resultFile = Path.of(System.getProperty("kansokusha.external-api-fixture.result"));
        this.databaseFile = Path.of(System.getProperty("kansokusha.external-api-fixture.database"));

        try {
            var result = registerAndSubmit();
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> verifyAfterShutdown(result), "kansokusha-external-api-fixture")
            );
        } catch (Throwable failure) {
            writeFailure(failure);
        } finally {
            Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
        }
    }

    private Result registerAndSubmit() {
        var api = Kansokusha.api();
        var definition = PaperKansokusha.eventType(EVENT_TYPE, PayloadGeneration.FIRST);
        if (api.registerEventType(definition) != RegistrationOutcome.REGISTERED) {
            throw new AssertionError("External event type registration did not succeed.");
        }

        var submission = new EventSubmission(
            PaperKansokusha.key(EVENT_TYPE),
            PayloadGeneration.FIRST,
            Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS),
            api.localServerKey().orElseThrow(),
            null,
            null,
            null,
            EventPayload.copyOf(new byte[]{1, 2, 3})
        );

        if (api.submit(submission) != SubmissionOutcome.ACCEPTED) {
            throw new AssertionError("External event submission was not accepted.");
        }
        if (!EVENT_TYPE.equals(PaperKansokusha.namespacedKey(submission.eventType()))) {
            throw new AssertionError("NamespacedKey conversion was not lossless.");
        }

        return new Result(api, definition, submission);
    }

    private void verifyAfterShutdown(Result result) {
        try {
            try {
                Kansokusha.api();
                throw new AssertionError("Kansokusha.api() remained available after shutdown.");
            } catch (IllegalStateException expected) {
            }

            if (result.api().submit(result.submission()) != SubmissionOutcome.CLOSED) {
                throw new AssertionError("Previously acquired API did not return CLOSED after shutdown.");
            }
            if (result.api().registerEventType(result.definition()) != RegistrationOutcome.CLOSED) {
                throw new AssertionError("Previously acquired API registration did not return CLOSED after shutdown.");
            }

            verifyPersistedEvent(result.submission());
            Files.writeString(this.resultFile, "success");
        } catch (Throwable failure) {
            writeFailure(failure);
        }
    }

    private void verifyPersistedEvent(EventSubmission submission) throws Exception {
        if (!Files.isRegularFile(this.databaseFile)) {
            throw new AssertionError("Kansokusha database was not created: " + this.databaseFile);
        }

        Class.forName("org.duckdb.DuckDBDriver");
        try (
            var connection = DriverManager.getConnection(
                "jdbc:duckdb:" + this.databaseFile.toAbsolutePath().normalize()
            );
            var statement = connection.prepareStatement(
                """
                    SELECT
                        et.event_type_key,
                        pg.generation,
                        epoch_ms(e.occurred_at) AS occurred_ms,
                        s.server_key,
                        hex(e.payload) AS payload_hex
                    FROM events e
                    JOIN payload_generations pg ON pg.id = e.payload_generation_id
                    JOIN event_types et ON et.id = pg.event_type_id
                    JOIN servers s ON s.id = e.server_id
                    WHERE et.event_type_key = ?
                    """
            )
        ) {
            statement.setString(1, submission.eventType().asString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("Accepted external event was not flushed to DuckDB.");
                }
                if (!submission.eventType().asString().equals(rows.getString("event_type_key"))) {
                    throw new AssertionError("Persisted event type key did not match.");
                }
                if (submission.payloadGeneration().value() != rows.getInt("generation")) {
                    throw new AssertionError("Persisted payload generation did not match.");
                }
                if (submission.occurredAt().toEpochMilli() != rows.getLong("occurred_ms")) {
                    throw new AssertionError("Persisted occurrence time did not match.");
                }
                if (!submission.serverKey().asString().equals(rows.getString("server_key"))) {
                    throw new AssertionError("Persisted server key did not match.");
                }
                if (!"010203".equals(rows.getString("payload_hex"))) {
                    throw new AssertionError("Persisted payload did not match.");
                }
                if (rows.next()) {
                    throw new AssertionError("External fixture event was persisted more than once.");
                }
            }
        }
    }

    private void writeFailure(Throwable failure) {
        try {
            Files.createDirectories(this.resultFile.getParent());
            var stackTrace = new StringWriter();
            failure.printStackTrace(new PrintWriter(stackTrace));
            Files.writeString(this.resultFile, "failure\n" + stackTrace);
        } catch (Exception writeFailure) {
            failure.addSuppressed(writeFailure);
            failure.printStackTrace();
        }
    }

    private record Result(
        KansokushaApi api,
        net.okocraft.kansokusha.api.event.EventTypeDefinition definition,
        EventSubmission submission
    ) {
    }
}
