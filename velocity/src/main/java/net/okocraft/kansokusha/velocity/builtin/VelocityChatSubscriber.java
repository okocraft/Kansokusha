package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/**
 * Records the original content observed by Velocity's player chat event.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class VelocityChatSubscriber {

    static final Key EVENT_TYPE = Key.key("kansokusha", "velocity_chat");

    private final KansokushaApi api;
    private final Clock clock;

    private VelocityChatSubscriber(KansokushaApi api, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static VelocityChatSubscriber register(KansokushaApi api) {
        return register(api, Clock.systemUTC());
    }

    static VelocityChatSubscriber register(KansokushaApi api, Clock clock) {
        VelocityBuiltInSupport.register(api, EVENT_TYPE);
        return new VelocityChatSubscriber(api, clock);
    }

    @Subscribe(async = false)
    public void record(PlayerChatEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = this.clock.instant();
        var player = event.getPlayer();
        var playerId = player.getUniqueId();
        var originalMessage = event.getMessage();

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                null,
                null,
                null,
                new PlayerActor(playerId),
                null,
                VelocityCommunicationPayloadCodec.encodeChat(originalMessage)
            )
        );
    }
}
