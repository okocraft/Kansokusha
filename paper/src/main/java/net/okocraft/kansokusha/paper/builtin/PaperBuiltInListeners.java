package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBuiltInListeners {

    static final List<BiFunction<KansokushaApi, Key, Listener>> FACTORIES = List.of(
        PaperBlockBreakListener::register,
        PaperBlockPlaceListener::register,
        PaperSignChangeListener::register,
        PaperBucketListener::register,
        PaperBlockHarvestListener::register,
        PaperFlowerPotChangeListener::register,
        PaperPlayerItemAuditListener::register,
        PaperPlayerContainerTransactionListener::register,
        PaperPlayerWorkstationAuditListener::register,
        PaperPlayerBlockInteractionListener::register,
        PaperBlockIgniteListener::register,
        PaperBlockBurnListener::register,
        PaperTntPrimeListener::register,
        PaperExplosionBlockChangeListener::register,
        PaperPistonMoveListener::register,
        PaperEntityBlockChangeListener::register,
        PaperNaturalBlockChangeListener::register,
        PaperFluidChangeListener::register,
        PaperSpongeAbsorbListener::register,
        PaperBlockFertilizeListener::register,
        PaperCauldronLevelChangeListener::register,
        PaperContainerTransferListener::register,
        PaperContainerPickupListener::register,
        PaperContainerProcessListener::register,
        PaperDispenserAuditListener::register,
        PaperPlayerJoinListener::register,
        PaperPlayerQuitListener::register,
        PaperPlayerKickListener::register,
        PaperPlayerWorldChangeListener::register,
        PaperPlayerTeleportListener::register,
        PaperPlayerGameModeChangeListener::register,
        PaperPlayerSpawnChangeListener::register,
        PaperPlayerDeathListener::register,
        PaperChatListener::register,
        PaperPlayerCommandListener::register,
        PaperServerCommandListener::register,
        PaperEntityPlaceListener::register,
        PaperEntityBreakListener::register,
        PaperEntityLifecycleAuditListener::register,
        PaperEntityStateChangeListener::register,
        PaperGameRuleChangeListener::register,
        PaperWorldDifficultyChangeListener::register,
        PaperWorldBorderChangeListener::register,
        PaperWorldSpawnChangeListener::register,
        PaperWhitelistChangeListener::register
    );

    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(10);

    private PaperBuiltInListeners() {
    }

    /**
     * Registers every built-in event type and listener. Bukkit unregisters the listeners when the plugin is disabled.
     *
     * @return an action that submits the events still buffered by aggregating listeners; run it before closing the API
     */
    public static Runnable registerAll(Plugin plugin, KansokushaApi api, Key serverKey) {
        var buffered = new ArrayList<PaperBufferedListener>();
        for (var factory : FACTORIES) {
            var listener = factory.apply(api, serverKey);
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            if (listener instanceof PaperBufferedListener bufferedListener) {
                buffered.add(bufferedListener);
            }
        }

        plugin.getServer().getAsyncScheduler().runAtFixedRate(
            plugin,
            task -> buffered.forEach(PaperBufferedListener::flushDue),
            FLUSH_INTERVAL.toSeconds(),
            FLUSH_INTERVAL.toSeconds(),
            TimeUnit.SECONDS
        );
        return () -> buffered.forEach(PaperBufferedListener::flushAll);
    }
}
