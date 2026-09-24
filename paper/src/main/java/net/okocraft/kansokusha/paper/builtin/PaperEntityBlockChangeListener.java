package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityBlockChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "entity_block_change");

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<EntityChangeBlockEvent, Snapshot> inFlight = new PaperInFlightMap<>();

    private PaperEntityBlockChangeListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperEntityBlockChangeListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperEntityBlockChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperEntityBlockChangeListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(EntityChangeBlockEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        var actor = event.getEntity();
        var to = event.getBlockData().clone();
        if (
            block.getType() == Material.TNT
                && actor instanceof Projectile
                && to.getMaterial().isAir()
        ) {
            return;
        }

        var actorId = actor.getUniqueId();
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ()),
            actor instanceof Player ? new PlayerSubject(actorId) : null,
            PaperWorldMutationPayloadCodec.encodeEntityBlockChange(
                block.getBlockData().clone(),
                to,
                actorId,
                actor.getType().name()
            )
        );

        this.inFlight.put(event, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(EntityChangeBlockEvent event) {
        Objects.requireNonNull(event, "event");
        var snapshot = this.inFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        this.api.submit(new EventSubmission(
            EVENT_TYPE,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            snapshot.serverKey(),
            snapshot.worldKey(),
            snapshot.position(),
            snapshot.subject(),
            snapshot.payload()
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
        EventPayload payload
    ) {
    }
}
