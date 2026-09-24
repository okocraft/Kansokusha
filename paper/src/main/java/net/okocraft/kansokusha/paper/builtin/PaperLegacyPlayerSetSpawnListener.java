package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Legacy Paper compatibility alternative for player spawn changes.
 *
 * <p>Register this listener only when the canonical PlayerSpawnChangeEvent path is unavailable.
 * Registering both would duplicate one logical spawn-point change on Paper versions that emit both
 * callbacks.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
@SuppressWarnings({"deprecation", "removal"})
final class PaperLegacyPlayerSetSpawnListener implements PaperInFlightListener {

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<com.destroystokyo.paper.event.player.PlayerSetSpawnEvent, Snapshot>
        inFlight = new PaperInFlightMap<>();

    private PaperLegacyPlayerSetSpawnListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static PaperLegacyPlayerSetSpawnListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, PaperPlayerSpawnChangeListener.EVENT_TYPE);
        return new PaperLegacyPlayerSetSpawnListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getPlayer();
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.snapshotOptionalLocation(player.getRespawnLocation()),
            PaperPlayerStatePayloadCodec.snapshotOptionalLocation(event.getLocation()),
            event.isForced(),
            PaperPlayerStatePayloadCodec.enumName(event.getCause())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var after = PaperPlayerStatePayloadCodec.snapshotOptionalLocation(event.getLocation());
        var common = after != null ? after : snapshot.before();
        this.api.submit(new EventSubmission(
            PaperPlayerSpawnChangeListener.EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            common == null ? null : common.worldKey(),
            common == null ? null : common.blockPosition(),
            snapshot.subject(),
            PaperPlayerStatePayloadCodec.encodeSpawnChange(
                snapshot.before(),
                after,
                event.isForced(),
                snapshot.cause(),
                "legacy_player_set_spawn"
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
        PaperPlayerStatePayloadCodec.LocationSnapshot before,
        PaperPlayerStatePayloadCodec.LocationSnapshot initialAfter,
        boolean initialForced,
        String cause
    ) {
    }
}
