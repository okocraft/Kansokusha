package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static net.okocraft.kansokusha.paper.builtin.PaperBuiltInSupport.position;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperCauldronLevelChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "cauldron_level_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<CauldronLevelChangeEvent, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperCauldronLevelChangeListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperCauldronLevelChangeListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperCauldronLevelChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperCauldronLevelChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(CauldronLevelChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var entity = event.getEntity();
        var entityId = entity == null ? null : entity.getUniqueId();
        var subject = entity instanceof Player
            ? new PlayerSubject(Objects.requireNonNull(entityId))
            : null;
        var actorKind = entity == null
            ? "none"
            : entity instanceof Player ? "player" : "entity";
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            subject,
            block.getBlockData().clone(),
            event.getReason().name(),
            actorKind,
            entityId,
            entity == null ? null : entity.getType().name()
        );

        this.inFlight.put(event, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(CauldronLevelChangeEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);

        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var payload = PaperBlockEventPayloadCodec.encodeCauldronLevelChange(
            snapshot.oldState(),
            event.getNewState().getBlockData().clone(),
            snapshot.reason(),
            snapshot.actorKind(),
            snapshot.entityId(),
            snapshot.entityType()
        );
        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            snapshot.serverKey(),
            snapshot.worldKey(),
            snapshot.position(),
            snapshot.subject(),
            payload
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
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        @Nullable PlayerSubject subject,
        BlockData oldState,
        String reason,
        String actorKind,
        @Nullable UUID entityId,
        @Nullable String entityType
    ) {
    }
}
