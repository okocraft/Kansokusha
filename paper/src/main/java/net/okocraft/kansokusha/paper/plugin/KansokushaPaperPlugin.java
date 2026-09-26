package net.okocraft.kansokusha.paper.plugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.paper.builtin.PaperBuiltInListeners;
import net.okocraft.kansokusha.paper.command.KansokushaCommands;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.logging.Level;

public final class KansokushaPaperPlugin extends JavaPlugin {

    private @Nullable KansokushaRuntime runtime;

    @Override
    public void onEnable() {
        try {
            LanguageProvider.load(this.getDataPath().resolve("languages"), KansokushaCommands.getDefiners());

            var config = KansokushaConfig.load(this.getDataPath());
            var serverKey = PaperServerIdentity.resolve(config.serverKey(), Path.of("."));
            var runtime = KansokushaRuntime.start(
                this.getDataPath(),
                config,
                serverKey,
                message -> this.getLogger().info(message),
                (message, failure) -> this.getLogger().log(Level.SEVERE, message, failure)
            );
            this.runtime = runtime;
            this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                Commands commands = event.registrar();
                KansokushaCommands.register(commands);
            });
            PaperBuiltInListeners.registerAll(this, runtime, serverKey);
            Kansokusha.setApi(runtime);
        } catch (IOException | SQLException | RuntimeException e) {
            LanguageProvider.unload();
            // onDisable closes the runtime if it has already started.
            this.getLogger().log(Level.SEVERE, "Failed to start Kansokusha.", e);
            this.getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        LanguageProvider.unload();

        var runtime = this.runtime;
        if (runtime != null) {
            Kansokusha.setApi(null);
            this.runtime = null;
            runtime.close();
        }
    }
}
