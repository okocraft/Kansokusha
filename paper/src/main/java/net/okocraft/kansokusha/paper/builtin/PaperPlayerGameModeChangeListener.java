package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/** Records an accepted old-to-new game-mode state transition. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperPlayerGameModeChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "player_gamemode_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerGameModeChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var oldMode = player.getGameMode();
        var newMode = event.getNewGameMode();
        if (oldMode == newMode) {
            return;
        }

        var location = PaperPlayerStatePayloadCodec.snapshotLocation(player.getLocation());
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            location.worldKey(),
            location.blockPosition(),
            new PlayerActor(player.getUniqueId()),
            null,
            PaperPlayerStatePayloadCodec.encodeGameModeChange(
                PaperPlayerStatePayloadCodec.enumName(oldMode),
                PaperPlayerStatePayloadCodec.enumName(newMode),
                PaperPlayerStatePayloadCodec.enumName(event.getCause())
            )
        ));
    }
}
