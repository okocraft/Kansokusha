package net.okocraft.kansokusha.velocity.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import net.okocraft.kansokusha.common.api.CommonKansokushaApiProvider;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.velocity.builtin.VelocityServerConnectedListener;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

public final class KansokushaVelocityPlugin {

    private final Logger logger;
    private final Path dataDirectory;
    private final KansokushaConfig.Holder config;

    private KansokushaRuntime runtime;
    private volatile VelocityServerConnectedListener serverConnectedListener;

    @Inject
    public KansokushaVelocityPlugin(Logger logger, @DataDirectory Path dataDirectory) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.config = new KansokushaConfig.Holder(dataDirectory);
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public synchronized void onProxyInitialize(ProxyInitializeEvent event) {
        if (this.runtime != null) {
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

        final KansokushaRuntime runtime;
        try {
            runtime = KansokushaRuntime.start(
                this.dataDirectory,
                (message, failure) -> this.logger.error(message, failure)
            );
        } catch (IOException | SQLException e) {
            this.logger.error("Failed to start Kansokusha runtime.", e);
            return;
        }

        try {
            if (!CommonKansokushaApiProvider.publish(runtime.api())) {
                throw new IllegalStateException("Kansokusha API is already published.");
            }
            this.serverConnectedListener = VelocityServerConnectedListener.register(runtime.api());
        } catch (RuntimeException | Error failure) {
            this.closeRuntime(runtime);
            throw failure;
        }

        this.runtime = runtime;
    }

    public synchronized boolean reloadRetentionPolicies() {
        var runtime = this.runtime;
        if (runtime == null) {
            return false;
        }

        try {
            runtime.reloadRetentionPolicies();
            this.logger.info("Reloaded retention policies.");
            return true;
        } catch (IOException | RuntimeException failure) {
            this.logger.error(
                "Failed to reload retention policies; the active policies were kept.",
                failure
            );
            return false;
        }
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public synchronized void onProxyShutdown(ProxyShutdownEvent event) {
        this.serverConnectedListener = null;
        var runtime = this.runtime;
        this.runtime = null;

        if (runtime != null) {
            this.closeRuntime(runtime);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        var listener = this.serverConnectedListener;
        if (listener != null) {
            listener.onServerConnected(event);
        }
    }

    private void closeRuntime(KansokushaRuntime runtime) {
        CommonKansokushaApiProvider.unpublish(runtime.api());
        try {
            runtime.close();
        } catch (SQLException | RuntimeException failure) {
            this.logger.error("Kansokusha runtime failed to shut down cleanly.", failure);
        }
    }
}
