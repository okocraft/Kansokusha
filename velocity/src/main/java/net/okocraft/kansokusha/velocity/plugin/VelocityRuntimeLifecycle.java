package net.okocraft.kansokusha.velocity.plugin;

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

    private final Path dataDirectory;
    private final AdministratorReporter reporter;
    private final RuntimeFactory runtimeFactory;

    @Nullable
    private KansokushaRuntime runtime;

    VelocityRuntimeLifecycle(
        Path dataDirectory,
        AdministratorReporter reporter
    ) {
        this(dataDirectory, reporter, KansokushaRuntime::start);
    }

    VelocityRuntimeLifecycle(
        Path dataDirectory,
        AdministratorReporter reporter,
        RuntimeFactory runtimeFactory
    ) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
    }

    void start() throws IOException, SQLException {
        if (this.runtime != null) {
            throw new IllegalStateException("Velocity runtime lifecycle has already been started.");
        }

        this.runtime = this.runtimeFactory.start(this.dataDirectory, this.reporter);
    }

    @Nullable
    KansokushaRuntime runtime() {
        return this.runtime;
    }

    @Override
    public void close() {
        var current = this.runtime;
        this.runtime = null;
        if (current == null) {
            return;
        }

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

    @FunctionalInterface
    interface RuntimeFactory {

        KansokushaRuntime start(
            Path dataDirectory,
            AdministratorReporter reporter
        ) throws IOException, SQLException;
    }
}
