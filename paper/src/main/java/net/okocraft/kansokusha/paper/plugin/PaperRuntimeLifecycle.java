package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.reporting.AdministratorReporter;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

final class PaperRuntimeLifecycle implements AutoCloseable {

    private static final String SHUTDOWN_FAILURE_MESSAGE =
        "Kansokusha runtime failed to shut down cleanly.";

    private final Path dataDirectory;
    private final Key serverKey;
    private final AdministratorReporter reporter;
    private final RuntimeFactory runtimeFactory;

    @Nullable
    private KansokushaRuntime runtime;

    PaperRuntimeLifecycle(
        Path dataDirectory,
        Key serverKey,
        AdministratorReporter reporter
    ) {
        this(dataDirectory, serverKey, reporter, KansokushaRuntime::start);
    }

    PaperRuntimeLifecycle(
        Path dataDirectory,
        Key serverKey,
        AdministratorReporter reporter,
        RuntimeFactory runtimeFactory
    ) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
    }

    void start() throws IOException, SQLException {
        if (this.runtime != null) {
            throw new IllegalStateException("Paper runtime lifecycle has already been started.");
        }

        this.runtime = this.runtimeFactory.start(this.dataDirectory, this.serverKey, this.reporter);
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
        }
    }

    @FunctionalInterface
    interface RuntimeFactory {

        KansokushaRuntime start(
            Path dataDirectory,
            Key serverKey,
            AdministratorReporter reporter
        ) throws IOException, SQLException;
    }
}
