package net.okocraft.kansokusha.paper.plugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.paper.builtin.PaperBuiltInListeners;
import net.okocraft.kansokusha.paper.command.KansokushaCommands;
import net.okocraft.kansokusha.paper.inspection.InspectionListener;
import net.okocraft.kansokusha.paper.inspection.InspectionSearchHandler;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
import net.okocraft.kansokusha.paper.notification.DiscordWebhook;
import net.okocraft.kansokusha.paper.notification.OreVeinListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.logging.Level;

public final class KansokushaPaperPlugin extends JavaPlugin {

    private final InspectionSessionManager inspectionSessions = new InspectionSessionManager();
    private @Nullable KansokushaRuntime runtime;
    private @Nullable Runnable flushBufferedEvents;
    private @Nullable DiscordWebhook discordWebhook;

    @Override
    public void onEnable() {
        try {
            LanguageProvider.load(this.getDataPath().resolve("languages"), KansokushaCommands.getDefaultMessages());

            var config = KansokushaConfig.load(this.getDataPath());
            this.getLogger().info("Search time zone: " + config.searchTimeZone());
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
                KansokushaCommands.register(
                    commands,
                    runtime,
                    config.searchTimeZone(),
                    this.inspectionSessions,
                    this
                );
            });
            InspectionListener.register(
                this,
                this.inspectionSessions,
                new InspectionSearchHandler(this.getServer(), runtime, java.time.Clock.systemUTC(), config.searchTimeZone())
            );
            this.getServer().getPluginManager().registerEvents(
                new net.okocraft.kansokusha.paper.command.CommandNavigation(), this);
            this.flushBufferedEvents = PaperBuiltInListeners.registerAll(this, runtime, serverKey);
            this.registerOreVeinListener(config, runtime, serverKey);
            Kansokusha.setApi(runtime);
        } catch (IOException | SQLException | RuntimeException e) {
            LanguageProvider.unload();
            this.getLogger().log(Level.SEVERE, "Failed to start Kansokusha.", e);
            this.getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerOreVeinListener(KansokushaConfig config, KansokushaRuntime runtime, Key serverKey) {
        var oreVein = config.oreVein();
        if (oreVein.enabled()) {
            this.discordWebhook = oreVein.webhookUrl().map(url -> new DiscordWebhook(url, this.getLogger())).orElse(null);
            this.getServer().getPluginManager().registerEvents(
                new OreVeinListener(oreVein, runtime, serverKey, this.discordWebhook), this);
        }
    }

    @Override
    public void onDisable() {
        if (this.discordWebhook != null) {
            this.discordWebhook.close();
            this.discordWebhook = null;
        }
        this.inspectionSessions.clear();
        net.okocraft.kansokusha.common.command.SearchNavigation.clear();
        LanguageProvider.unload();

        var runtime = this.runtime;
        if (runtime != null) {
            Kansokusha.setApi(null);
            this.runtime = null;
            var flush = this.flushBufferedEvents;
            this.flushBufferedEvents = null;
            if (flush != null) {
                flush.run();
            }
            runtime.close();
        }
    }
}
