package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockPlaceListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_place");
    static final Key ENTITY_EVENT_TYPE = Key.key("kansokusha", "entity_place");
    static final String ENTITY_PLACE_SOURCE_EVENT =
        "org.bukkit.event.entity.EntityPlaceEvent";
    static final String HANGING_PLACE_SOURCE_EVENT =
        "org.bukkit.event.hanging.HangingPlaceEvent";

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final PaperInFlightMap<BlockPlaceEvent, List<Snapshot>> inFlight = new PaperInFlightMap<>();
    private final PaperInFlightMap<EntityPlaceEvent, EntitySnapshot> entityInFlight =
        new PaperInFlightMap<>();
    private final PaperInFlightMap<HangingPlaceEvent, EntitySnapshot> hangingInFlight =
        new PaperInFlightMap<>();

    private PaperBlockPlaceListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockPlaceListener register(
        KansokushaApi api,
        Key serverKey
    ) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockPlaceListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        PaperBuiltInSupport.register(api, EVENT_TYPE, ENTITY_EVENT_TYPE);
        return new PaperBlockPlaceListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        var occurredAt = Instant.now(this.clock);
        var subject = new PlayerSubject(event.getPlayer().getUniqueId());
        List<Snapshot> snapshots;

        if (event instanceof BlockMultiPlaceEvent multiPlaceEvent) {
            var captured = new ArrayList<Snapshot>(multiPlaceEvent.getReplacedBlockStates().size());
            for (var replacedState : multiPlaceEvent.getReplacedBlockStates()) {
                captured.add(this.snapshot(replacedState, subject, occurredAt));
            }
            snapshots = List.copyOf(captured);
        } else {
            snapshots = List.of(
                this.snapshot(event.getBlockReplacedState(), subject, occurredAt)
            );
        }

        this.inFlight.put(event, snapshots);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshots = this.inFlight.remove(event);

        if (snapshots == null || event.isCancelled() || !event.canBuild()) {
            return;
        }

        for (var snapshot : snapshots) {
            this.submit(EVENT_TYPE, snapshot);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureEntityPlace(EntityPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.getEntity() instanceof Hanging) {
            return;
        }

        var entity = event.getEntity();
        var location = entity.getLocation();
        var player = event.getPlayer();
        var usedItem = player == null
            ? ItemStack.empty()
            : player.getInventory().getItem(event.getHand());

        this.entityInFlight.put(
            event,
            this.entitySnapshot(
                location,
                player,
                PaperEntityLifecyclePayloadCodec.encodePlacement(
                    entity,
                    location,
                    player,
                    event.getHand(),
                    usedItem,
                    ENTITY_PLACE_SOURCE_EVENT,
                    null,
                    null
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEntityPlace(EntityPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        var snapshot = this.entityInFlight.remove(event);
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        this.submitEntity(snapshot);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureHangingPlace(HangingPlaceEvent event) {
        Objects.requireNonNull(event, "event");

        var player = event.getPlayer();
        var location = event.getEntity().getLocation();
        this.hangingInFlight.put(
            event,
            this.entitySnapshot(
                location,
                player,
                PaperEntityLifecyclePayloadCodec.encodePlacement(
                    event.getEntity(),
                    location,
                    player,
                    event.getHand(),
                    event.getItemStack(),
                    HANGING_PLACE_SOURCE_EVENT,
                    event.getBlock(),
                    event.getBlockFace()
                )
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeHangingPlace(HangingPlaceEvent event) {
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

    private Snapshot snapshot(
        BlockState replacedState,
        PlayerSubject subject,
        Instant occurredAt
    ) {
        var placedBlock = replacedState.getBlock();
        return new Snapshot(
            occurredAt,
            this.serverKey,
            PaperKansokusha.key(replacedState.getWorld().getKey()),
            new BlockPosition(
                replacedState.getX(),
                replacedState.getY(),
                replacedState.getZ()
            ),
            subject,
            PaperBlockStatePayloadCodec.encodeBlockPlace(
                replacedState.getBlockData(),
                placedBlock.getBlockData()
            )
        );
    }

    private EntitySnapshot entitySnapshot(
        Location location,
        @Nullable Player player,
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
            player == null ? null : new PlayerSubject(player.getUniqueId()),
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
