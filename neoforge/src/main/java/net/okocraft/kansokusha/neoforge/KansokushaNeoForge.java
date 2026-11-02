package net.okocraft.kansokusha.neoforge;

import net.kyori.adventure.key.Key;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;

import java.nio.file.Path;

@Mod("kansokusha")
public final class KansokushaNeoForge {
    private static volatile KansokushaRuntime runtime;
    private static volatile NativeEventRecorder recorder;
    private static volatile java.time.ZoneId searchTimeZone;

    public KansokushaNeoForge() {
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
        NeoForge.EVENT_BUS.addListener(NativeCommands::register);
        NativeInspection.register();
    }

    static KansokushaRuntime runtime() { return runtime; }
    public static NativeEventRecorder recorder() { return recorder; }
    static java.time.ZoneId searchTimeZone() { return searchTimeZone; }

    private void start(ServerStartingEvent event) {
        var dataDirectory = FMLPaths.CONFIGDIR.get().resolve("kansokusha");
        try {
            var config = KansokushaConfig.load(dataDirectory);
            var serverKey = config.serverKey().orElseGet(() -> Key.key("kansokusha", Path.of(".").toAbsolutePath().normalize().getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
            LanguageProvider.load(dataDirectory.resolve("languages"), NativeCommands.defaultMessages());
            var opened = KansokushaRuntime.start(dataDirectory, config, serverKey,
                message -> System.getLogger("Kansokusha").log(System.Logger.Level.INFO, message),
                (message, failure) -> System.getLogger("Kansokusha").log(System.Logger.Level.ERROR, message, failure));
            recorder = new NativeEventRecorder(opened, serverKey);
            searchTimeZone = config.searchTimeZone();
            runtime = opened;
            Kansokusha.setApi(opened);
        } catch (Exception failure) {
            LanguageProvider.unload();
            throw new IllegalStateException("Kansokusha could not open its instance-local database", failure);
        }
    }

    private void stop(ServerStoppingEvent event) {
        var currentRecorder = recorder;
        if (currentRecorder != null) currentRecorder.flush();
        Kansokusha.setApi(null);
        var opened = runtime;
        runtime = null;
        recorder = null;
        NativeInspection.clear();
        LanguageProvider.unload();
        if (opened != null) opened.close();
    }
}
