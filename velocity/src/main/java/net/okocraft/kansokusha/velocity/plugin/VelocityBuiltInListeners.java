package net.okocraft.kansokusha.velocity.plugin;

import com.velocitypowered.api.event.EventManager;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.velocity.builtin.VelocityBackendRegistryChangeListener;
import net.okocraft.kansokusha.velocity.builtin.VelocityChatSubscriber;
import net.okocraft.kansokusha.velocity.builtin.VelocityCommandSubscriber;
import net.okocraft.kansokusha.velocity.builtin.VelocityPlayerSessionListener;
import net.okocraft.kansokusha.velocity.builtin.VelocityServerConnectedListener;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class VelocityBuiltInListeners implements AutoCloseable {

    private final EventManager eventManager;
    private final Object plugin;
    private final List<Object> listeners;
    private boolean closed;

    private VelocityBuiltInListeners(
        EventManager eventManager,
        Object plugin,
        List<Object> listeners
    ) {
        this.eventManager = eventManager;
        this.plugin = plugin;
        this.listeners = listeners;
    }

    static VelocityBuiltInListeners register(
        EventManager eventManager,
        Object plugin,
        KansokushaApi api,
        Logger logger
    ) {
        Objects.requireNonNull(eventManager, "eventManager");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(logger, "logger");

        var registered = new ArrayList<Object>(5);
        try {
            register(
                eventManager,
                plugin,
                registered,
                VelocityServerConnectedListener.register(api, logger)
            );
            register(
                eventManager,
                plugin,
                registered,
                VelocityPlayerSessionListener.register(api, logger)
            );
            register(
                eventManager,
                plugin,
                registered,
                VelocityChatSubscriber.register(api)
            );
            register(
                eventManager,
                plugin,
                registered,
                VelocityCommandSubscriber.register(api)
            );
            register(
                eventManager,
                plugin,
                registered,
                VelocityBackendRegistryChangeListener.register(api, logger)
            );
        } catch (RuntimeException | Error failure) {
            rollback(eventManager, plugin, registered, failure);
            throw failure;
        }

        return new VelocityBuiltInListeners(
            eventManager,
            plugin,
            List.copyOf(registered)
        );
    }

    private static void register(
        EventManager eventManager,
        Object plugin,
        List<Object> registered,
        Object listener
    ) {
        eventManager.register(plugin, listener);
        registered.add(listener);
    }

    private static void rollback(
        EventManager eventManager,
        Object plugin,
        List<Object> registered,
        Throwable primaryFailure
    ) {
        for (var index = registered.size() - 1; index >= 0; index--) {
            try {
                eventManager.unregisterListener(plugin, registered.get(index));
            } catch (RuntimeException | Error cleanupFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        Throwable firstFailure = null;
        for (var index = this.listeners.size() - 1; index >= 0; index--) {
            try {
                this.eventManager.unregisterListener(
                    this.plugin,
                    this.listeners.get(index)
                );
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }

        if (firstFailure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
    }
}
