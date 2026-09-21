package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.paper.builtin.PaperBlockBreakListener;
import net.okocraft.kansokusha.paper.builtin.PaperBlockPlaceListener;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public final class KansokushaPaperPlugin extends JavaPlugin {

    private Key serverKey;
    private PaperBlockBreakListener blockBreakListener;
    private PaperBlockPlaceListener blockPlaceListener;
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
        PaperBlockBreakListener blockBreakListener = null;
        PaperBlockPlaceListener blockPlaceListener = null;

        try {
            lifecycle.start();

            blockBreakListener = PaperBlockBreakListener.register(
                lifecycle.api(),
                serverKey
            );
            blockPlaceListener = PaperBlockPlaceListener.register(
                lifecycle.api(),
                serverKey
            );

            var pluginManager = this.getServer().getPluginManager();
            pluginManager.registerEvents(blockBreakListener, this);
            pluginManager.registerEvents(blockPlaceListener, this);

            this.blockBreakListener = blockBreakListener;
            this.blockPlaceListener = blockPlaceListener;
            this.runtimeLifecycle = lifecycle;
        } catch (IOException | SQLException e) {
            cleanupListeners(blockPlaceListener, blockBreakListener);
            lifecycle.close();
            throw new IllegalStateException("Failed to start Kansokusha runtime.", e);
        } catch (RuntimeException | Error failure) {
            cleanupListeners(blockPlaceListener, blockBreakListener);
            lifecycle.close();
            throw failure;
        }
    }

    @Override
    public void onDisable() {
        var blockPlaceListener = this.blockPlaceListener;
        this.blockPlaceListener = null;
        var blockBreakListener = this.blockBreakListener;
        this.blockBreakListener = null;
        cleanupListeners(blockPlaceListener, blockBreakListener);

        var lifecycle = this.runtimeLifecycle;
        this.runtimeLifecycle = null;
        if (lifecycle != null) {
            lifecycle.close();
        }
    }

    private static void cleanupListeners(
        PaperBlockPlaceListener blockPlaceListener,
        PaperBlockBreakListener blockBreakListener
    ) {
        if (blockPlaceListener != null) {
            HandlerList.unregisterAll(blockPlaceListener);
            blockPlaceListener.clear();
        }
        if (blockBreakListener != null) {
            HandlerList.unregisterAll(blockBreakListener);
            blockBreakListener.clear();
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
