package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.paper.builtin.PaperBlockBreakListener;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public final class KansokushaPaperPlugin extends JavaPlugin {

    private Key serverKey;
    private PaperBlockBreakListener blockBreakListener;
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
        var serverKey = this.requireServerKey();
        var lifecycle = new PaperRuntimeLifecycle(
            this.getDataPath(),
            serverKey,
            reporter
        );

        try {
            lifecycle.start();

            var blockBreakListener = PaperBlockBreakListener.register(
                lifecycle.api(),
                serverKey
            );
            this.getServer().getPluginManager().registerEvents(blockBreakListener, this);

            this.blockBreakListener = blockBreakListener;
            this.runtimeLifecycle = lifecycle;
        } catch (IOException | SQLException e) {
            lifecycle.close();
            throw new IllegalStateException("Failed to start Kansokusha runtime.", e);
        } catch (RuntimeException | Error failure) {
            lifecycle.close();
            throw failure;
        }
    }

    @Override
    public void onDisable() {
        var blockBreakListener = this.blockBreakListener;
        this.blockBreakListener = null;
        if (blockBreakListener != null) {
            HandlerList.unregisterAll(blockBreakListener);
            blockBreakListener.clear();
        }

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
