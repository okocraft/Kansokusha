package net.okocraft.kansokusha.paper.builtin;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(PlayerSetSpawnEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var before = PaperPlayerStatePayloadCodec.snapshotOptionalLocation(player.getRespawnLocation());
        var after = PaperPlayerStatePayloadCodec.snapshotOptionalLocation(event.getLocation());
        var common = after != null ? after : before;
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            common == null ? null : common.worldKey(),
            common == null ? null : common.blockPosition(),
            new PlayerActor(player.getUniqueId()),
            null,
            PaperPlayerStatePayloadCodec.encodeSpawnChange(
                before,
                after,
                event.isForced(),
                PaperPlayerStatePayloadCodec.enumName(event.getCause())
            )
        ));
    }
}
