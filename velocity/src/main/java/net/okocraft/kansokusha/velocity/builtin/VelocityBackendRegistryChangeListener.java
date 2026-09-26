package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records backend server-map mutations observed after Velocity has initialized the plugin layer.
 *
 * <p>Velocity 4.2.0 populates CLI/configured static servers before loading plugins and only then
 * fires {@code ProxyInitializeEvent}. This subscriber is therefore intended to be registered by
 * the plugin initialization wiring: startup static population is outside its observable lifetime,
 * while later runtime and configuration-reload mutations still fire the registry events handled
 * here.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class VelocityBackendRegistryChangeListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "backend_registry_change");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Logger logger;
    private final Clock clock;
    private final Set<String> warnedServerNames = ConcurrentHashMap.newKeySet();

    private VelocityBackendRegistryChangeListener(KansokushaApi api, Logger logger, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static VelocityBackendRegistryChangeListener register(KansokushaApi api, Logger logger) {
        return register(api, logger, Clock.systemUTC());
    }

    static VelocityBackendRegistryChangeListener register(
        KansokushaApi api,
        Logger logger,
        Clock clock
    ) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(DEFINITION);
        if (
            outcome != RegistrationOutcome.REGISTERED
                && outcome != RegistrationOutcome.ALREADY_REGISTERED
        ) {
            throw new IllegalStateException(
                "Could not register built-in event type " + EVENT_TYPE + ": " + outcome
            );
        }
        return new VelocityBackendRegistryChangeListener(api, logger, clock);
    }

    @Subscribe
    public void onServerRegistered(ServerRegisteredEvent event) {
        Objects.requireNonNull(event, "event");
        this.record(
            VelocityBackendRegistryChangePayloadCodec.Action.REGISTER,
            event.registeredServer()
        );
    }

    @Subscribe
    public void onServerUnregistered(ServerUnregisteredEvent event) {
        Objects.requireNonNull(event, "event");
        this.record(
            VelocityBackendRegistryChangePayloadCodec.Action.UNREGISTER,
            event.unregisteredServer()
        );
    }

    private void record(
        VelocityBackendRegistryChangePayloadCodec.Action action,
        RegisteredServer server
    ) {
        var serverInfo = server.getServerInfo();
        var name = serverInfo.getName();
        var serverKey = VelocityServerKeyCodec.encode(name);
        if (serverKey.isEmpty()) {
            if (this.warnedServerNames.add(name)) {
                this.logger.warn(
                    "Backend registry changes for '{}' are not recorded: the lower-cased name is not a valid "
                        + "key value (allowed characters: a-z 0-9 _ . - /).",
                    name
                );
            }
            return;
        }

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                Instant.now(this.clock),
                serverKey.orElseThrow(),
                null,
                null,
                null,
                VelocityBackendRegistryChangePayloadCodec.encode(
                    action,
                    serverKey.orElseThrow(),
                    serverInfo
                )
            )
        );
    }
}
