package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;
import java.util.stream.Collectors;

/** Records successful teleport operations, independently of world-change state transitions. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerTeleportListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_teleport");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperPlayerTeleportListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerTeleportListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerTeleportListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerTeleportListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerTeleportEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof PlayerPortalEvent) {
            return;
        }

        var destination = PaperPlayerStatePayloadCodec.snapshotLocation(event.getTo());
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            destination.worldKey(),
            destination.blockPosition(),
            new PlayerActor(event.getPlayer().getUniqueId()),
            null,
            PaperPlayerStatePayloadCodec.encodeTeleport(
                PaperPlayerStatePayloadCodec.snapshotLocation(event.getFrom()),
                destination,
                PaperPlayerStatePayloadCodec.enumName(event.getCause()),
                event.getRelativeTeleportationFlags().stream()
                    .map(PaperPlayerStatePayloadCodec::enumName)
                    .collect(Collectors.toUnmodifiableSet())
            )
        ));
    }
}
