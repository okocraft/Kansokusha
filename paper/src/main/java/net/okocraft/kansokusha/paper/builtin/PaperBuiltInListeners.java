package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
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
        PaperEntityStateChangeListener::register,
        PaperGameRuleChangeListener::register,
        PaperWorldDifficultyChangeListener::register,
        PaperWorldBorderChangeListener::register,
        PaperWorldSpawnChangeListener::register,
        PaperWhitelistChangeListener::register
    );

    private PaperBuiltInListeners() {
    }

    /**
     * Registers every built-in event type and listener. Bukkit unregisters the listeners when the plugin is disabled.
     */
    public static void registerAll(Plugin plugin, KansokushaApi api, Key serverKey) {
        for (var factory : FACTORIES) {
            plugin.getServer().getPluginManager().registerEvents(factory.apply(api, serverKey), plugin);
        }
    }
}
