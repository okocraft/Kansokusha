package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBuiltInListenerCollection implements AutoCloseable {

    private static final List<Factory> FACTORIES = List.of(
        PaperBlockBreakListener::register,
        PaperBlockPlaceListener::register,
        PaperSignChangeListener::register,
        PaperBucketListener::register,
        PaperBlockHarvestListener::register,
        PaperFlowerPotChangeListener::register,
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

    private final Registrar registrar;
    private final List<Listener> listeners = new ArrayList<>(FACTORIES.size());
    private boolean closed;

    private PaperBuiltInListenerCollection(Registrar registrar) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
    }

    public static PaperBuiltInListenerCollection register(
        Plugin plugin,
        KansokushaApi api,
        Key serverKey
    ) {
        Objects.requireNonNull(plugin, "plugin");
        return register(api, serverKey, new BukkitRegistrar(plugin));
    }

    static PaperBuiltInListenerCollection register(
        KansokushaApi api,
        Key serverKey,
        Registrar registrar
    ) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(serverKey, "serverKey");
        var collection = new PaperBuiltInListenerCollection(registrar);

        try {
            for (var factory : FACTORIES) {
                collection.register(factory.create(api, serverKey));
            }
            return collection;
        } catch (RuntimeException | Error failure) {
            try {
                collection.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void register(Listener listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        this.registrar.register(listener);
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        Throwable failure = null;
        for (int i = this.listeners.size() - 1; i >= 0; i--) {
            try {
                this.registrar.unregister(this.listeners.get(i));
            } catch (RuntimeException | Error cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }

        for (int i = this.listeners.size() - 1; i >= 0; i--) {
            var listener = this.listeners.get(i);
            if (listener instanceof PaperInFlightListener inFlightListener) {
                try {
                    inFlightListener.clearInFlightState();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
            }
        }
        this.listeners.clear();

        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable appendFailure(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    @FunctionalInterface
    private interface Factory {
        Listener create(KansokushaApi api, Key serverKey);
    }

    interface Registrar {

        void register(Listener listener);

        void unregister(Listener listener);
    }

    private record BukkitRegistrar(Plugin plugin) implements Registrar {

        private BukkitRegistrar {
            Objects.requireNonNull(plugin, "plugin");
        }

        @Override
        public void register(Listener listener) {
            this.plugin.getServer().getPluginManager().registerEvents(listener, this.plugin);
            if (listener instanceof PaperEntityBreakListener entityBreakListener) {
                entityBreakListener.registerGenericCallbacks(this.plugin);
            }
        }

        @Override
        public void unregister(Listener listener) {
            HandlerList.unregisterAll(listener);
        }
    }
}
