package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.entity.EntityBreakByEntityEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperEntityBreakListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "entity_break");
    static final String GENERIC_SOURCE_EVENT =
        "io.papermc.paper.event.entity.EntityBreakByEntityEvent";
    static final String HANGING_SOURCE_EVENT =
        "org.bukkit.event.hanging.HangingBreakByEntityEvent";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<EntityBreakByEntityEvent, Snapshot> genericInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<HangingBreakByEntityEvent, Snapshot> hangingInFlight =
        new PaperInFlightMap<>();

    private PaperEntityBreakListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperEntityBreakListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperEntityBreakListener register(KansokushaApi api, Key serverKey, Clock clock) {
        PaperBuiltInSupport.register(api, EVENT_TYPE);
        return new PaperEntityBreakListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureGeneric(EntityBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.getEntity() instanceof Hanging) {
            return;
        }

        var damageSource = event.getDamageSource();
        var remover = event.getRemover();
        this.genericInFlight.put(
            event,
            this.snapshot(
                PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity()),
                PaperEntityEventPayloadCodec.snapshotEntity(remover),
                remover instanceof Player player ? player : null,
                event.getCause().name().toLowerCase(Locale.ROOT),
                damageSource.getDamageType().getKey().toString(),
                damageSource.isIndirect(),
                GENERIC_SOURCE_EVENT,
                false
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeGeneric(EntityBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(event.isCancelled(), this.genericInFlight.remove(event));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureHanging(HangingBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var damageSource = event.getDamageSource();
        var remover = event.getRemover();
        this.hangingInFlight.put(
            event,
            this.snapshot(
                PaperEntityEventPayloadCodec.snapshotEntity(event.getEntity()),
                PaperEntityEventPayloadCodec.snapshotEntity(remover),
                remover instanceof Player player ? player : null,
                event.getCause().name().toLowerCase(Locale.ROOT),
                damageSource.getDamageType().getKey().toString(),
                damageSource.isIndirect(),
                HANGING_SOURCE_EVENT,
                true
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeHanging(HangingBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");
        this.finalizeEvent(event.isCancelled(), this.hangingInFlight.remove(event));
    }

    @Override
    public void clearInFlightState() {
        this.genericInFlight.clear();
        this.hangingInFlight.clear();
    }

    int inFlightCount() {
        return this.genericInFlight.size() + this.hangingInFlight.size();
    }

    private Snapshot snapshot(
        PaperEntityEventPayloadCodec.EntitySnapshot brokenEntity,
        PaperEntityEventPayloadCodec.EntitySnapshot breaker,
        @Nullable Player player,
        String cause,
        String damageType,
        boolean indirectDamage,
        String sourceEvent,
        boolean hanging
    ) {
        return new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            brokenEntity.worldKey(),
            new BlockPosition(
                (int) Math.floor(brokenEntity.x()),
                (int) Math.floor(brokenEntity.y()),
                (int) Math.floor(brokenEntity.z())
            ),
            player == null ? null : new PlayerSubject(player.getUniqueId()),
            PaperEntityEventPayloadCodec.encodeBreak(
                brokenEntity,
                breaker,
                cause,
                damageType,
                indirectDamage,
                sourceEvent,
                hanging
            )
        );
    }

    private void finalizeEvent(boolean cancelled, @Nullable Snapshot snapshot) {
        if (snapshot == null || cancelled) {
            return;
        }

        this.api.submit(
            new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                snapshot.subject(),
                snapshot.payload()
            )
        );
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
