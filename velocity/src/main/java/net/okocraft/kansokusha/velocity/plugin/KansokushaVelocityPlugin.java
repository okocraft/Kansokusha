package net.okocraft.kansokusha.velocity.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.velocity.builtin.VelocityBackendRegistryChangeListener;
import net.okocraft.kansokusha.velocity.builtin.VelocityChatSubscriber;
import net.okocraft.kansokusha.velocity.builtin.VelocityCommandSubscriber;
import net.okocraft.kansokusha.velocity.builtin.VelocityPlayerSessionListener;
import net.okocraft.kansokusha.velocity.builtin.VelocityServerConnectedListener;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

public final class KansokushaVelocityPlugin {

    private final Logger logger;
    private final ProxyServer proxyServer;
    private final Path dataDirectory;

    private @Nullable KansokushaRuntime runtime;

    @Inject
    public KansokushaVelocityPlugin(Logger logger, ProxyServer proxyServer, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.proxyServer = proxyServer;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        final KansokushaRuntime runtime;
        try {
            runtime = KansokushaRuntime.start(
                this.dataDirectory,
                KansokushaConfig.load(this.dataDirectory),
                null,
                (message, failure) -> this.logger.error(message, failure)
            );
        } catch (IOException | SQLException e) {
            this.logger.error("Failed to start Kansokusha.", e);
            return;
        }
        this.runtime = runtime;

        var listeners = List.of(
            VelocityServerConnectedListener.register(runtime, this.logger),
            VelocityPlayerSessionListener.register(runtime, this.logger),
            VelocityChatSubscriber.register(runtime),
            VelocityCommandSubscriber.register(runtime),
            VelocityBackendRegistryChangeListener.register(runtime, this.logger)
        );
        for (var listener : listeners) {
            this.proxyServer.getEventManager().register(this, listener);
        }

        Kansokusha.setApi(runtime);
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        var runtime = this.runtime;
        if (runtime != null) {
            Kansokusha.setApi(null);
            this.runtime = null;
            runtime.close();
        }
    }
}
