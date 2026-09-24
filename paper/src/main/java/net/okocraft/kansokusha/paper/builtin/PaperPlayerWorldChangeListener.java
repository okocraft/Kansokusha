package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/**
 * Records a completed gameplay world transition.
 *
 * <p>This is intentionally separate from {@link PlayerTeleportEvent}: a cross-world teleport may
 * produce both records. This event states that the player's world changed, while the teleport
 * record describes the teleport operation that produced a destination.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerWorldChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_world_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerWorldChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerWorldChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerWorldChangeListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerWorldChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void record(PlayerChangedWorldEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var destination = PaperPlayerStatePayloadCodec.snapshotLocation(player.getLocation());
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            destination.worldKey(),
            destination.blockPosition(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.encodeWorldChange(
                PaperKansokusha.key(event.getFrom().getKey()),
                destination
            )
        ));
    }
}
