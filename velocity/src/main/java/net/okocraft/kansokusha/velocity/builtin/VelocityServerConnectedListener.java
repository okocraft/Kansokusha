package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityServerConnectedListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "server_connected");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Logger logger;
    private final Clock clock;
    private final Set<String> warnedServerNames = ConcurrentHashMap.newKeySet();

    private VelocityServerConnectedListener(KansokushaApi api, Logger logger, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static VelocityServerConnectedListener register(KansokushaApi api, Logger logger) {
        return register(api, logger, Clock.systemUTC());
    }

    static VelocityServerConnectedListener register(KansokushaApi api, Logger logger, Clock clock) {
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
        return new VelocityServerConnectedListener(api, logger, clock);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Objects.requireNonNull(event, "event");

        var targetServerKey = this.serverKey(event.getServer());
        if (targetServerKey == null) {
            return;
        }

        Key previousServerKey = null;
        var previousServer = event.getPreviousServer();
        if (previousServer.isPresent()) {
            previousServerKey = this.serverKey(previousServer.get());
            if (previousServerKey == null) {
                return;
            }
        }

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                Instant.now(this.clock),
                targetServerKey,
                null,
                null,
                new PlayerSubject(event.getPlayer().getUniqueId()),
                VelocityServerConnectedPayloadCodec.encode(previousServerKey)
            )
        );
    }

    private @Nullable Key serverKey(RegisteredServer server) {
        var name = server.getServerInfo().getName();
        var key = VelocityServerKeyCodec.encode(name);
        if (key.isEmpty() && this.warnedServerNames.add(name)) {
            this.logger.warn(
                "Server connections to or from '{}' are not recorded: the lower-cased name is not a valid "
                    + "key value (allowed characters: a-z 0-9 _ . - /).",
                name
            );
        }
        return key.orElse(null);
    }
}
