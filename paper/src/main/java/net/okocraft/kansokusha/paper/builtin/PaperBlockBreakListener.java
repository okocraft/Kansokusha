package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.entity.EntityBreakByEntityEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockBreakListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_break");
    static final Key ENTITY_EVENT_TYPE = Key.key("kansokusha", "entity_break");
    static final String ENTITY_BREAK_SOURCE_EVENT =
        "io.papermc.paper.event.entity.EntityBreakByEntityEvent";
    static final String HANGING_BREAK_SOURCE_EVENT =
        "org.bukkit.event.hanging.HangingBreakByEntityEvent";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<BlockBreakEvent, Snapshot> inFlight = new PaperInFlightMap<>();
    private final PaperInFlightMap<EntityBreakByEntityEvent, EntitySnapshot> entityInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<HangingBreakByEntityEvent, EntitySnapshot> hangingInFlight =
        new PaperInFlightMap<>();

    private PaperBlockBreakListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockBreakListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockBreakListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE, ENTITY_EVENT_TYPE);
        return new PaperBlockBreakListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockBreakEvent event) {
        Objects.requireNonNull(event, "event");

        var block = event.getBlock();
        var snapshot = new Snapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            new BlockPosition(block.getX(), block.getY(), block.getZ()),
            new PlayerSubject(event.getPlayer().getUniqueId()),
            PaperBlockStatePayloadCodec.encodeBlockBreak(block.getBlockData())
        );

        this.inFlight.put(event, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockBreakEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.inFlight.remove(event);

        if (snapshot == null || event.isCancelled()) {
            return;
        }

        this.submit(EVENT_TYPE, snapshot);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureEntityBreak(EntityBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.getEntity() instanceof Hanging) {
            return;
        }

        var broken = event.getEntity();
        var location = broken.getLocation();
        this.entityInFlight.put(
            event,
            this.entitySnapshot(
                location,
                event.getRemover(),
                PaperEntityLifecyclePayloadCodec.encodeBreak(
                    broken,
                    location,
                    event.getRemover(),
                    event.getCause().name(),
                    event.getDamageSource(),
                    ENTITY_BREAK_SOURCE_EVENT
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEntityBreak(EntityBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.entityInFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        this.submitEntity(snapshot);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureHangingBreak(HangingBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var broken = event.getEntity();
        var location = broken.getLocation();
        this.hangingInFlight.put(
            event,
            this.entitySnapshot(
                location,
                event.getRemover(),
                PaperEntityLifecyclePayloadCodec.encodeBreak(
                    broken,
                    location,
                    event.getRemover(),
                    event.getCause().name(),
                    event.getDamageSource(),
                    HANGING_BREAK_SOURCE_EVENT
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeHangingBreak(HangingBreakByEntityEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.hangingInFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        this.submitEntity(snapshot);
    }

    @Override
    public void clearInFlightState() {
        this.inFlight.clear();
        this.entityInFlight.clear();
        this.hangingInFlight.clear();
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    int entityInFlightCount() {
        return this.entityInFlight.size() + this.hangingInFlight.size();
    }

    private EntitySnapshot entitySnapshot(
        Location location,
        org.bukkit.entity.Entity breaker,
        EventPayload payload
    ) {
        var world = Objects.requireNonNull(location.getWorld(), "location.world");
        return new EntitySnapshot(
            Instant.now(this.clock),
            this.serverKey,
            PaperKansokusha.key(world.getKey()),
            new BlockPosition(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
            ),
            breaker instanceof Player player
                ? new PlayerSubject(player.getUniqueId())
                : null,
            payload
        );
    }

    private void submit(Key eventType, Snapshot snapshot) {
        this.api.submit(
            new EventSubmission(
                eventType,
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

    private void submitEntity(EntitySnapshot snapshot) {
        this.api.submit(
            new EventSubmission(
                ENTITY_EVENT_TYPE,
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
        PlayerSubject subject,
        EventPayload payload
    ) {
    }

    private record EntitySnapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        @Nullable PlayerSubject subject,
        EventPayload payload
    ) {
    }
}
