package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent;
import io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Records accepted world-border center and requested bounds changes. */
@ApiStatus.Internal
@NotNullByDefault
public final class PaperWorldBorderChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "world_border_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<WorldBorderCenterChangeEvent, CenterSnapshot> centerInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<WorldBorderBoundsChangeEvent, BoundsSnapshot> boundsInFlight =
        new PaperInFlightMap<>();

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

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureCenter(WorldBorderCenterChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var oldCenter = event.getOldCenter();
        this.centerInFlight.put(event, new CenterSnapshot(
            this.clock.instant(),
            PaperKansokusha.key(event.getWorld().getKey()),
            PaperAdministrativePayloadCodec.center(oldCenter.getX(), oldCenter.getZ())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeCenter(WorldBorderCenterChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.centerInFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var newCenter = event.getNewCenter();
        var after = PaperAdministrativePayloadCodec.center(newCenter.getX(), newCenter.getZ());
        if (snapshot.before().equals(after)) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            snapshot.worldKey(),
            null,
            null,
            PaperAdministrativePayloadCodec.encodeBorderCenterChange(
                snapshot.before(),
                after
            )
        ));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureBounds(WorldBorderBoundsChangeEvent event) {
        Objects.requireNonNull(event, "event");

        this.boundsInFlight.put(event, new BoundsSnapshot(
            this.clock.instant(),
            PaperKansokusha.key(event.getWorld().getKey()),
            event.getOldSize()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeBounds(WorldBorderBoundsChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.boundsInFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var after = event.getNewSize();
        if (Double.compare(snapshot.before(), after) == 0) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            this.serverKey,
            snapshot.worldKey(),
            null,
            null,
            PaperAdministrativePayloadCodec.encodeBorderBoundsChange(
                snapshot.before(),
                after,
                PaperAdministrativePayloadCodec.enumName(event.getType()),
                event.getDurationTicks()
            )
        ));
    }

    @Override
    public void clearInFlightState() {
        this.centerInFlight.clear();
        this.boundsInFlight.clear();
    }

    int inFlightCount() {
        return this.centerInFlight.size() + this.boundsInFlight.size();
    }

    private record CenterSnapshot(
        Instant occurredAt,
        Key worldKey,
        PaperAdministrativePayloadCodec.CenterSnapshot before
    ) {
    }

    private record BoundsSnapshot(Instant occurredAt, Key worldKey, double before) {
    }
}
