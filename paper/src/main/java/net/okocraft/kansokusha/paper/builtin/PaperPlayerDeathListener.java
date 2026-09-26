package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Records accepted player deaths without duplicating the full death-drop inventory payload.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerDeathListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_death");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<PlayerDeathEvent, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperPlayerDeathListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerDeathListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerDeathListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerDeathListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(PlayerDeathEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getPlayer();
        var damageEvent = player.getLastDamageCause();
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.snapshotLocation(player.getLocation()),
            PaperPlayerStatePayloadCodec.snapshotKiller(
                event.getDamageSource().getCausingEntity()
            ),
            damageEvent == null
                ? null
                : PaperPlayerStatePayloadCodec.enumName(damageEvent.getCause())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(PlayerDeathEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            snapshot.location().worldKey(),
            snapshot.location().blockPosition(),
            snapshot.subject(),
            PaperPlayerStatePayloadCodec.encodeDeath(
                event.deathMessage(),
                snapshot.killer(),
                snapshot.lastDamageCause(),
                event.getDroppedExp(),
                event.getNewExp(),
                event.getNewTotalExp(),
                event.getNewLevel(),
                event.getKeepInventory(),
                event.getKeepLevel()
            )
        ));
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    private record Snapshot(
        Instant occurredAt,
        PlayerSubject subject,
        PaperPlayerStatePayloadCodec.LocationSnapshot location,
        @Nullable PaperPlayerStatePayloadCodec.KillerSnapshot killer,
        @Nullable String lastDamageCause
    ) {
    }
}
