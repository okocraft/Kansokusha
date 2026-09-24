package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Records successful teleport operations, independently of world-change state transitions. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerTeleportListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_teleport");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<PlayerTeleportEvent, Snapshot> inFlight = new PaperInFlightMap<>();

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

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(PlayerTeleportEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof PlayerPortalEvent) {
            return;
        }
        var player = event.getPlayer();
        var initialTo = event.getTo();
        var snapshot = new Snapshot(
            this.clock.instant(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.snapshotLocation(event.getFrom()),
            PaperPlayerStatePayloadCodec.snapshotOptionalLocation(initialTo),
            PaperPlayerStatePayloadCodec.enumName(event.getCause()),
            event.getRelativeTeleportationFlags().stream()
                .map(PaperPlayerStatePayloadCodec::enumName)
                .collect(Collectors.toUnmodifiableSet())
        );
        this.inFlight.put(event, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(PlayerTeleportEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof PlayerPortalEvent) {
            return;
        }
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var finalTo = event.getTo();
        if (finalTo == null) {
            return;
        }
        var destination = PaperPlayerStatePayloadCodec.snapshotLocation(finalTo);
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            destination.worldKey(),
            destination.blockPosition(),
            snapshot.subject(),
            PaperPlayerStatePayloadCodec.encodeTeleport(
                snapshot.from(),
                destination,
                snapshot.cause(),
                snapshot.relativeFlags()
            )
        ));
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private record Snapshot(
        Instant occurredAt,
        PlayerSubject subject,
        PaperPlayerStatePayloadCodec.LocationSnapshot from,
        PaperPlayerStatePayloadCodec.LocationSnapshot initialTo,
        String cause,
        Set<String> relativeFlags
    ) {
    }
}
