package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Records an accepted old-to-new game-mode state transition. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerGameModeChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_gamemode_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<PlayerGameModeChangeEvent, Snapshot> inFlight =
        new PaperInFlightMap<>();

    private PaperPlayerGameModeChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperPlayerGameModeChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperPlayerGameModeChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperPlayerGameModeChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(PlayerGameModeChangeEvent event) {
        Objects.requireNonNull(event, "event");
        var player = event.getPlayer();
        this.inFlight.put(event, new Snapshot(
            this.clock.instant(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.snapshotLocation(player.getLocation()),
            player.getGameMode(),
            event.getNewGameMode(),
            PaperPlayerStatePayloadCodec.enumName(event.getCause())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(PlayerGameModeChangeEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var finalMode = event.getNewGameMode();
        if (snapshot.oldMode() == finalMode) {
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
            PaperPlayerStatePayloadCodec.encodeGameModeChange(
                PaperPlayerStatePayloadCodec.enumName(snapshot.oldMode()),
                PaperPlayerStatePayloadCodec.enumName(finalMode),
                snapshot.cause()
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
        PaperPlayerStatePayloadCodec.LocationSnapshot location,
        GameMode oldMode,
        GameMode initialNewMode,
        String cause
    ) {
    }
}
