package net.okocraft.kansokusha.paper.plugin;

import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class KansokushaPaperPlugin extends JavaPlugin {

    private KansokushaConfig.Holder config;

    @Override
    public void onLoad() {
        this.config = new KansokushaConfig.Holder(this.getDataPath());

        try {
            this.config.reload();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config.yml", e);
        }

        if (this.config.get().debug()) {
            this.getLogger().info("Debug mode enabled");
        }
    }
}
