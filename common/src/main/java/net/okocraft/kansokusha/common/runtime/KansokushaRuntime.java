package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.common.api.BoundedEventIntake;
import net.okocraft.kansokusha.common.api.DefaultKansokushaApi;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.event.RetentionPolicySet;
import net.okocraft.kansokusha.common.event.registry.InMemoryRuntimeEventTypeRegistry;
import net.okocraft.kansokusha.common.retention.RetentionCleanupService;
import net.okocraft.kansokusha.common.storage.DuckDbDatabase;
import net.okocraft.kansokusha.common.storage.DuckDbEventWriter;
import net.okocraft.kansokusha.common.storage.DuckDbMigrations;
import net.okocraft.kansokusha.common.storage.DuckDbRetentionCleaner;
import net.okocraft.kansokusha.common.writer.AsyncBatchWriterService;
import net.okocraft.kansokusha.common.writer.PipelineFailureReporter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@NotNullByDefault
public final class KansokushaRuntime implements AutoCloseable {

    static final String DATABASE_FILENAME = "kansokusha.duckdb";

    private final DefaultKansokushaApi api;
    private final AsyncBatchWriterService writer;
    private final RetentionCleanupService cleanup;
    private final DuckDbDatabase database;
    private final AtomicBoolean closed = new AtomicBoolean();

    private KansokushaRuntime(
        DefaultKansokushaApi api,
        AsyncBatchWriterService writer,
        RetentionCleanupService cleanup,
        DuckDbDatabase database
    ) {
        this.api = api;
        this.writer = writer;
        this.cleanup = cleanup;
        this.database = database;
    }

    public static KansokushaRuntime start(
        Path dataDirectory,
        Key localServerKey,
        PipelineFailureReporter failureReporter
    ) throws IOException, SQLException {
        return start(
            dataDirectory,
            Optional.of(Objects.requireNonNull(localServerKey, "localServerKey")),
            failureReporter
        );
    }

    public static KansokushaRuntime start(
        Path dataDirectory,
        PipelineFailureReporter failureReporter
    ) throws IOException, SQLException {
        return start(dataDirectory, Optional.empty(), failureReporter);
    }

    private static KansokushaRuntime start(
        Path dataDirectory,
        Optional<Key> localServerKey,
        PipelineFailureReporter failureReporter
    ) throws IOException, SQLException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(localServerKey, "localServerKey");
        Objects.requireNonNull(failureReporter, "failureReporter");

        DuckDbDatabase database = null;
        AsyncBatchWriterService writer = null;
        RetentionCleanupService cleanup = null;
        DefaultKansokushaApi api = null;

        try {
            var configHolder = new KansokushaConfig.Holder(dataDirectory);
            configHolder.reload();
            var config = configHolder.get();

            database = DuckDbDatabase.open(dataDirectory.resolve(DATABASE_FILENAME));
            DuckDbMigrations.migrate(database);

            var retentionPolicies = RetentionPolicySet.from(config.retentionSettings());
            var ingestion = config.ingestionSettings();
            var intake = new BoundedEventIntake(ingestion.queueCapacity(), retentionPolicies);
            var registry = new InMemoryRuntimeEventTypeRegistry();
            api = localServerKey
                .map(key -> new DefaultKansokushaApi(registry, intake, key))
                .orElseGet(() -> new DefaultKansokushaApi(registry, intake));

            writer = new AsyncBatchWriterService(
                intake,
                new DuckDbEventWriter(database),
                failureReporter,
                ingestion.maxBatchSize(),
                ingestion.maxBatchDelay()
            );

            var cleanupSettings = config.retentionCleanupSettings();
            cleanup = new RetentionCleanupService(
                new DuckDbRetentionCleaner(database),
                failureReporter::report,
                cleanupSettings.interval(),
                cleanupSettings.maxRowsPerPass()
            );

            writer.start();
            cleanup.start();

            return new KansokushaRuntime(api, writer, cleanup, database);
        } catch (IOException | SQLException | RuntimeException | Error failure) {
            closeAfterStartupFailure(api, cleanup, writer, database, failureReporter, failure);
            throw failure;
        }
    }

    public KansokushaApi api() {
        return this.api;
    }

    @Override
    public void close() throws SQLException {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        this.api.close();

        Throwable failure = null;
        try {
            this.cleanup.close();
        } catch (RuntimeException | Error e) {
            failure = e;
        }

        try {
            this.writer.drainAndStop();
        } catch (RuntimeException | Error e) {
            failure = suppress(failure, e);
        }

        try {
            this.database.close();
        } catch (SQLException | RuntimeException | Error e) {
            failure = suppress(failure, e);
        }

        if (failure instanceof SQLException sqlException) {
            throw sqlException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void closeAfterStartupFailure(
        DefaultKansokushaApi api,
        RetentionCleanupService cleanup,
        AsyncBatchWriterService writer,
        DuckDbDatabase database,
        PipelineFailureReporter failureReporter,
        Throwable failure
    ) {
        if (api != null) {
            api.close();
        }

        if (cleanup != null) {
            try {
                cleanup.close();
            } catch (Throwable closeFailure) {
                addSuppressed(failure, closeFailure);
            }
        }

        if (writer != null) {
            try {
                writer.drainAndStop();
            } catch (Throwable closeFailure) {
                addSuppressed(failure, closeFailure);
            }
        }

        if (database != null) {
            try {
                database.close();
            } catch (Throwable closeFailure) {
                addSuppressed(failure, closeFailure);
            }
        }

        try {
            failureReporter.report(failure);
        } catch (Throwable reportingFailure) {
            addSuppressed(failure, reportingFailure);
        }
    }

    private static Throwable suppress(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        addSuppressed(primary, secondary);
        return primary;
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }
}
