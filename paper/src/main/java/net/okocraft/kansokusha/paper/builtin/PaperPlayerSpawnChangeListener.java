package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerSpawnChangeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical Paper 26.2 player spawn-change recorder.
 *
 * <p>Paper can expose a legacy PlayerSetSpawnEvent for the same operation. Production wiring must
 * register this canonical listener when available and must not register the legacy compatibility
 * listener at the same time.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
@SuppressWarnings({"deprecation", "removal"})
public final class PaperPlayerSpawnChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_spawn_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<PlayerSpawnChangeEvent, Snapshot> inFlight =
        new PaperInFlightMap<>();

    private PaperPlayerSpawnChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerSpawnChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerSpawnChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerSpawnChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(PlayerSpawnChangeEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getPlayer();
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.snapshotOptionalLocation(player.getRespawnLocation()),
            PaperPlayerStatePayloadCodec.snapshotOptionalLocation(event.getNewSpawn()),
            event.isForced(),
            PaperPlayerStatePayloadCodec.enumName(event.getCause())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(PlayerSpawnChangeEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var after = PaperPlayerStatePayloadCodec.snapshotOptionalLocation(event.getNewSpawn());
        var common = after != null ? after : snapshot.before();
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
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
                "player_spawn_change"
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
