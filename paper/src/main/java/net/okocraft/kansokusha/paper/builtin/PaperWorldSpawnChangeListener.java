package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.SpawnChangeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/**
 * Records completed changes to a world's global spawn.
 *
 * <p>This is intentionally distinct from the per-player respawn point recorded by
 * {@link PaperPlayerSpawnChangeListener}.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperWorldSpawnChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "world_spawn_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperWorldSpawnChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperWorldSpawnChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperWorldSpawnChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperWorldSpawnChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void record(SpawnChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var world = event.getWorld();
        var before = PaperPlayerStatePayloadCodec.snapshotLocation(event.getPreviousLocation());
        var after = PaperPlayerStatePayloadCodec.snapshotLocation(world.getSpawnLocation());
        if (before.equals(after)) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            after.worldKey(),
            after.blockPosition(),
            null,
            PaperAdministrativePayloadCodec.encodeWorldSpawnChange(before, after)
        ));
    }
}
