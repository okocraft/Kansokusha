package net.okocraft.kansokusha.velocity.plugin;

import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.common.reporting.AdministratorReporter;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

final class VelocityRuntimeLifecycle implements AutoCloseable {

    private static final String SHUTDOWN_FAILURE_MESSAGE =
        "Kansokusha runtime failed to shut down cleanly.";

    private static final ApiPublication SERVICE_PUBLICATION = new ApiPublication() {
        @Override
        public void publish(KansokushaApi api) {
            VelocityKansokushaApiProvider.publish(api);
        }

        @Override
        public void unpublish(KansokushaApi api) {
            VelocityKansokushaApiProvider.unpublish(api);
        }
    };

    private final Path dataDirectory;
    private final AdministratorReporter reporter;
    private final RuntimeFactory runtimeFactory;
    private final ApiPublication apiPublication;

    @Nullable
    private KansokushaRuntime runtime;

    VelocityRuntimeLifecycle(
        Path dataDirectory,
        AdministratorReporter reporter
    ) {
        this(dataDirectory, reporter, KansokushaRuntime::start, SERVICE_PUBLICATION);
    }

    VelocityRuntimeLifecycle(
        Path dataDirectory,
        AdministratorReporter reporter,
        RuntimeFactory runtimeFactory
    ) {
        this(dataDirectory, reporter, runtimeFactory, SERVICE_PUBLICATION);
    }

    VelocityRuntimeLifecycle(
        Path dataDirectory,
        AdministratorReporter reporter,
        RuntimeFactory runtimeFactory,
        ApiPublication apiPublication
    ) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.apiPublication = Objects.requireNonNull(apiPublication, "apiPublication");
    }

    void start() throws IOException, SQLException {
        if (this.runtime != null) {
            throw new IllegalStateException("Velocity runtime lifecycle has already been started.");
        }

        var started = this.runtimeFactory.start(this.dataDirectory, this.reporter);
        try {
            this.apiPublication.publish(started.api());
        } catch (RuntimeException | Error failure) {
            closeAfterPublicationFailure(started, failure);
            throw failure;
        }

        this.runtime = started;
    }

    @Nullable
    KansokushaRuntime runtime() {
        return this.runtime;
    }

    KansokushaApi api() {
        var current = this.runtime;
        if (current == null) {
            throw new IllegalStateException("Velocity runtime lifecycle is not started.");
        }
        return current.api();
    }

    void reloadRetentionPolicies() throws IOException {
        var current = this.runtime;
        if (current == null) {
            throw new IllegalStateException("Velocity runtime lifecycle is not started.");
        }
        current.reloadRetentionPolicies();
    }

    @Override
    public void close() {
        var current = this.runtime;
        this.runtime = null;
        if (current == null) {
            return;
        }

        this.apiPublication.unpublish(current.api());

        try {
            current.close();
        } catch (SQLException | RuntimeException failure) {
            this.reporter.report(SHUTDOWN_FAILURE_MESSAGE, failure);
        } catch (Error failure) {
            try {
                this.reporter.report(SHUTDOWN_FAILURE_MESSAGE, failure);
            } catch (Throwable reportingFailure) {
                if (reportingFailure != failure) {
                    failure.addSuppressed(reportingFailure);
                }
            }
            throw failure;
        }
    }

    private static void closeAfterPublicationFailure(
        KansokushaRuntime runtime,
        Throwable failure
    ) {
        try {
            runtime.close();
        } catch (SQLException | RuntimeException | Error closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    @FunctionalInterface
    interface RuntimeFactory {

        KansokushaRuntime start(
            Path dataDirectory,
            AdministratorReporter reporter
        ) throws IOException, SQLException;
    }

    interface ApiPublication {

        void publish(KansokushaApi api);

        void unpublish(KansokushaApi api);
    }
}
