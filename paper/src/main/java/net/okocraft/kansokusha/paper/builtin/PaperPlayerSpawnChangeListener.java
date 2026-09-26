package net.okocraft.kansokusha.paper.builtin;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Records the effective Paper 26.2 player spawn-point change.
 *
 * <p>Paper fires the deprecated Bukkit PlayerSpawnChangeEvent first, then carries its values into
 * PlayerSetSpawnEvent. The latter remains mutable/cancellable and is the final event boundary
 * before the respawn state is applied, so only that event is recorded.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
@SuppressWarnings({"deprecation", "removal"})
public final class PaperPlayerSpawnChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_spawn_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<PlayerSetSpawnEvent, Snapshot> inFlight =
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
    public void capture(PlayerSetSpawnEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getPlayer();
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.snapshotOptionalLocation(player.getRespawnLocation()),
            PaperPlayerStatePayloadCodec.enumName(event.getCause())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(PlayerSetSpawnEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var after = PaperPlayerStatePayloadCodec.snapshotOptionalLocation(event.getLocation());
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
                "player_set_spawn"
            )
        ));
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private record Snapshot(
        Instant occurredAt,
        PlayerSubject subject,
        PaperPlayerStatePayloadCodec.LocationSnapshot before,
        String cause
    ) {
    }
}
