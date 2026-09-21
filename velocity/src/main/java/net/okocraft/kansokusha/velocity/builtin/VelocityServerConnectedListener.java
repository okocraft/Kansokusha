package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class VelocityServerConnectedListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "server_connected");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Clock clock;

    private VelocityServerConnectedListener(KansokushaApi api, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static VelocityServerConnectedListener register(KansokushaApi api) {
        return register(api, Clock.systemUTC());
    }

    static VelocityServerConnectedListener register(KansokushaApi api, Clock clock) {
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
        return new VelocityServerConnectedListener(api, clock);
    }

    public void onServerConnected(ServerConnectedEvent event) {
        Objects.requireNonNull(event, "event");

        var targetServerKey = VelocityServerKeyCodec.encode(
            event.getServer().getServerInfo().getName()
        );
        var previousServerKey = event.getPreviousServer()
            .map(server -> VelocityServerKeyCodec.encode(server.getServerInfo().getName()))
            .orElse(null);

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
}
