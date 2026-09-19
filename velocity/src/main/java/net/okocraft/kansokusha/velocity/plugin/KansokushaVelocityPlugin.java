package net.okocraft.kansokusha.velocity.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

public final class KansokushaVelocityPlugin {

    private final Logger logger;
    private final KansokushaConfig.Holder config;

    @Inject
    public KansokushaVelocityPlugin(Logger logger, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.config = new KansokushaConfig.Holder(dataDirectory);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            this.config.reload();
        } catch (IOException e) {
            this.logger.error("Failed to load config.yml", e);
            return;
        }

        if (this.config.get().debug()) {
            this.logger.info("Debug mode enabled");
        }
    }
}
