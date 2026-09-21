package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public final class KansokushaPaperPlugin extends JavaPlugin {

    private Key serverKey;
    private PaperRuntimeLifecycle runtimeLifecycle;

    @Override
    public void onLoad() {
        var config = new KansokushaConfig.Holder(this.getDataPath());

        try {
            config.reload();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config.yml", e);
        }

        var loaded = config.get();
        this.serverKey = PaperServerIdentity.resolve(loaded.localServerKey(), Path.of("."));

        if (loaded.debug()) {
            this.getLogger().info("Debug mode enabled");
        }
    }

    @Override
    public void onEnable() {
        var reporter = new PaperAdministratorReporter(this.getLogger());
        var lifecycle = new PaperRuntimeLifecycle(
            this.getDataPath(),
            this.requireServerKey(),
            reporter
        );

        try {
            lifecycle.start();
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to start Kansokusha runtime.", e);
        }

        this.runtimeLifecycle = lifecycle;
    }

    @Override
    public void onDisable() {
        var lifecycle = this.runtimeLifecycle;
        this.runtimeLifecycle = null;
        if (lifecycle != null) {
            lifecycle.close();
        }
    }

    private Key requireServerKey() {
        var key = this.serverKey;
        if (key == null) {
            throw new IllegalStateException("Paper/Folia server identity has not been loaded.");
        }
        return key;
    }
}
