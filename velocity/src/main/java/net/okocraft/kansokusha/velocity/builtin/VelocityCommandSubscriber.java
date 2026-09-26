package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Objects;

/**
 * Records command input as observed at Velocity's pre-execution boundary.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class VelocityCommandSubscriber {

    static final Key EVENT_TYPE = Key.key("kansokusha", "velocity_command");

    private final KansokushaApi api;
    private final Clock clock;

    private VelocityCommandSubscriber(KansokushaApi api, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static VelocityCommandSubscriber register(KansokushaApi api) {
        return register(api, Clock.systemUTC());
    }

    static VelocityCommandSubscriber register(KansokushaApi api, Clock clock) {
        VelocityBuiltInSupport.register(api, EVENT_TYPE);
        return new VelocityCommandSubscriber(api, clock);
    }

    @Subscribe(async = false)
    public void record(CommandExecuteEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = this.clock.instant();
        var originalCommand = event.getCommand();
        var source = snapshotSource(event);

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                null,
                null,
                null,
                source.actor(),
                null,
                VelocityCommunicationPayloadCodec.encodeCommand(
                    source.kind(),
                    source.name(),
                    originalCommand
                )
            )
        );
    }

    private static SourceSnapshot snapshotSource(CommandExecuteEvent event) {
        var source = event.getCommandSource();

        if (source instanceof Player player) {
            return new SourceSnapshot(
                "player",
                player.getUsername(),
                new PlayerActor(player.getUniqueId())
            );
        }

        if (source instanceof ConsoleCommandSource) {
            return new SourceSnapshot("console", "CONSOLE", null);
        }

        var kind = event.getInvocationInfo().source() == CommandExecuteEvent.Source.API
            ? "api"
            : "other";
        return new SourceSnapshot(kind, sourceName(source), null);
    }

    private static String sourceName(CommandSource source) {
        return source.get(Identity.NAME).orElseGet(() -> source.getClass().getName());
    }

    private record SourceSnapshot(
        String kind,
        String name,
        @Nullable PlayerActor actor
    ) {
    }
}
