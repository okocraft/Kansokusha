package net.okocraft.kansokusha.paper.plugin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.api.CommonKansokushaApiProvider;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.paper.builtin.PaperBuiltInListenerCollection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.logging.Level;

public final class KansokushaPaperPlugin extends JavaPlugin {

    private Key serverKey;
    private PaperBuiltInListenerCollection builtInListeners;
    private KansokushaRuntime runtime;

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
        var serverKey = this.requireServerKey();
        final KansokushaRuntime runtime;

        try {
            runtime = KansokushaRuntime.start(
                this.getDataPath(),
                serverKey,
                (message, failure) -> this.getLogger().log(Level.SEVERE, message, failure)
            );
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to start Kansokusha runtime.", e);
        }

        final PaperBuiltInListenerCollection builtInListeners;
        try {
            if (!CommonKansokushaApiProvider.publish(runtime.api())) {
                throw new IllegalStateException("Kansokusha API is already published.");
            }

            builtInListeners = PaperBuiltInListenerCollection.register(
                this,
                runtime.api(),
                serverKey
            );
        } catch (RuntimeException | Error failure) {
            this.closeRuntime(runtime);
            throw failure;
        }

        this.builtInListeners = builtInListeners;
        this.runtime = runtime;
    }

    public boolean reloadRetentionPolicies() {
        var runtime = this.runtime;
        if (runtime == null) {
            return false;
        }

        try {
            runtime.reloadRetentionPolicies();
            this.getLogger().info("Reloaded retention policies.");
            return true;
        } catch (IOException | RuntimeException failure) {
            this.getLogger().log(
                Level.SEVERE,
                "Failed to reload retention policies; the active policies were kept.",
                failure
            );
            return false;
        }
    }

    @Override
    public void onDisable() {
        var builtInListeners = this.builtInListeners;
        this.builtInListeners = null;
        var runtime = this.runtime;
        this.runtime = null;

        try {
            if (builtInListeners != null) {
                builtInListeners.close();
            }
        } catch (RuntimeException failure) {
            this.getLogger().log(
                Level.SEVERE,
                "Kansokusha built-in listeners failed to shut down cleanly.",
                failure
            );
        } finally {
            if (runtime != null) {
                this.closeRuntime(runtime);
            }
        }
    }

    private void closeRuntime(KansokushaRuntime runtime) {
        CommonKansokushaApiProvider.unpublish(runtime.api());
        try {
            runtime.close();
        } catch (SQLException | RuntimeException failure) {
            this.getLogger().log(Level.SEVERE, "Kansokusha runtime failed to shut down cleanly.", failure);
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
