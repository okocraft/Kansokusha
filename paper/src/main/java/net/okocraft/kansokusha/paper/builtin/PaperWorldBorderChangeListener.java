package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent;
import io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.util.Objects;

/** Records accepted world-border center and requested bounds changes. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperWorldBorderChangeListener implements Listener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "world_border_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;

    private PaperWorldBorderChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperWorldBorderChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperWorldBorderChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperWorldBorderChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordCenter(WorldBorderCenterChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var oldCenter = event.getOldCenter();
        var newCenter = event.getNewCenter();
        var before = PaperAdministrativePayloadCodec.center(oldCenter.getX(), oldCenter.getZ());
        var after = PaperAdministrativePayloadCodec.center(newCenter.getX(), newCenter.getZ());
        if (before.equals(after)) {
            return;
        }

        this.submit(
            event.getWorld(),
            PaperAdministrativePayloadCodec.encodeBorderCenterChange(before, after)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordBounds(WorldBorderBoundsChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var before = event.getOldSize();
        var after = event.getNewSize();
        if (Double.compare(before, after) == 0) {
            return;
        }

        var durationTicks = event.getDurationTicks();
        var transitionType =
            event.getType() == WorldBorderBoundsChangeEvent.Type.STARTED_MOVE
                && durationTicks > 0
                ? WorldBorderBoundsChangeEvent.Type.STARTED_MOVE
                : WorldBorderBoundsChangeEvent.Type.INSTANT_MOVE;

        this.submit(
            event.getWorld(),
            PaperAdministrativePayloadCodec.encodeBorderBoundsChange(
                before,
                after,
                PaperAdministrativePayloadCodec.enumName(transitionType),
                durationTicks
            )
        );
    }

    private void submit(World world, EventPayload payload) {
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(world.getKey()),
            null,
            null,
            null,
            payload
        ));
    }
}
