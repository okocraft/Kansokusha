package net.okocraft.kansokusha.velocity.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.reporting.AdministratorReporter;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

public final class KansokushaVelocityPlugin {

    private final Logger logger;
    private final Path dataDirectory;
    private final KansokushaConfig.Holder config;
    private final LifecycleFactory lifecycleFactory;
    private final Object lifecycleMonitor = new Object();

    private VelocityRuntimeLifecycle runtimeLifecycle;
    private boolean shutdownStarted;

    @Inject
    public KansokushaVelocityPlugin(Logger logger, @DataDirectory Path dataDirectory) {
        this(logger, dataDirectory, VelocityRuntimeLifecycle::new);
    }

    KansokushaVelocityPlugin(
        Logger logger,
        Path dataDirectory,
        LifecycleFactory lifecycleFactory
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.lifecycleFactory = Objects.requireNonNull(lifecycleFactory, "lifecycleFactory");
        this.config = new KansokushaConfig.Holder(dataDirectory);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        synchronized (this.lifecycleMonitor) {
            if (this.shutdownStarted || this.runtimeLifecycle != null) {
                return;
            }

            try {
                this.config.reload();
            } catch (IOException e) {
                this.logger.error("Failed to load config.yml", e);
                return;
            }

            if (this.config.get().debug()) {
                this.logger.info("Debug mode enabled");
            }

            var lifecycle = this.lifecycleFactory.create(
                this.dataDirectory,
                new VelocityAdministratorReporter(this.logger)
            );

            try {
                lifecycle.start();
            } catch (IOException | SQLException e) {
                this.logger.error("Failed to start Kansokusha runtime.", e);
                return;
            }

            this.runtimeLifecycle = lifecycle;
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        final VelocityRuntimeLifecycle lifecycle;
        synchronized (this.lifecycleMonitor) {
            this.shutdownStarted = true;
            lifecycle = this.runtimeLifecycle;
            this.runtimeLifecycle = null;
        }

        if (lifecycle != null) {
            lifecycle.close();
        }
    }

    @FunctionalInterface
    interface LifecycleFactory {

        VelocityRuntimeLifecycle create(
            Path dataDirectory,
            AdministratorReporter reporter
        );
    }
}
