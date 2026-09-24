package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/**
 * Records player command input as observed at Paper's preprocess boundary.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerCommandListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "paper_player_command");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerCommandListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerCommandListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerCommandListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerCommandListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void record(PlayerCommandPreprocessEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = this.clock.instant();
        var originalCommand = event.getMessage();
        var player = event.getPlayer();
        var playerId = player.getUniqueId();
        var location = player.getLocation();
        var world = Objects.requireNonNull(location.getWorld(), "player.location.world");

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                occurredAt,
                this.serverKey,
                PaperKansokusha.key(world.getKey()),
                new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                new PlayerSubject(playerId),
                PaperCommunicationPayloadCodec.encodePlayerCommand(originalCommand)
            )
        );
    }
}
