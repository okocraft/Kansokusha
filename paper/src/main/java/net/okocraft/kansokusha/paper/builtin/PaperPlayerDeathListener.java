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

import java.time.Clock;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerDeathEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var location = PaperPlayerStatePayloadCodec.snapshotLocation(player.getLocation());
        var damageEvent = player.getLastDamageCause();
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            location.worldKey(),
            location.blockPosition(),
            new PlayerSubject(player.getUniqueId()),
            PaperPlayerStatePayloadCodec.encodeDeath(
                event.deathMessage(),
                PaperPlayerStatePayloadCodec.snapshotKiller(
                    event.getDamageSource().getCausingEntity()
                ),
                damageEvent == null
                    ? null
                    : PaperPlayerStatePayloadCodec.enumName(damageEvent.getCause()),
                event.getDroppedExp(),
                event.getNewExp(),
                event.getNewTotalExp(),
                event.getNewLevel(),
                event.getKeepInventory(),
                event.getKeepLevel()
            )
        ));
    }
}
