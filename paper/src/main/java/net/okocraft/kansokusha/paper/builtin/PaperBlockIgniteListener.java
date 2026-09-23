package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockIgniteListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_ignite");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final Map<BlockIgniteEvent, Snapshot> inFlight = new IdentityHashMap<>();
    private final Map<PlayerPlacementKey, Snapshot> pendingPlayerPlacements = new HashMap<>();

    private PaperBlockIgniteListener(KansokushaApi api, Key serverKey, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PaperBlockIgniteListener register(KansokushaApi api, Key serverKey) {
        return register(api, serverKey, Clock.systemUTC());
    }

    static PaperBlockIgniteListener register(KansokushaApi api, Key serverKey, Clock clock) {
        registerEventType(api, DEFINITION);
        return new PaperBlockIgniteListener(api, serverKey, clock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockIgniteEvent event) {
        Objects.requireNonNull(event, "event");

        var cause = event.getCause();
        if (cause == BlockIgniteEvent.IgniteCause.SPREAD) {
            return;
        }

        var block = event.getBlock();
        var source = source(event.getIgnitingBlock());
        var entity = event.getIgnitingEntity();
        var player = event.getPlayer();
        var playerId = player == null ? null : player.getUniqueId();
        var payload = PaperBlockEventPayloadCodec.encodeIgnite(
            block.getBlockData(),
            cause.name(),
            source == null ? null : source.position(),
            source == null ? null : source.state(),
            entity == null ? null : entity.getUniqueId(),
            entity == null ? null : entity.getType().name()
        );
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            playerId == null ? null : new PlayerSubject(playerId),
            payload,
            awaitsPlayerPlacement(cause, playerId) ? playerId : null
        );

        synchronized (this.inFlight) {
            this.inFlight.put(event, snapshot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockIgniteEvent event) {
        Objects.requireNonNull(event, "event");
        Snapshot snapshot;
        synchronized (this.inFlight) {
            snapshot = this.inFlight.remove(event);
        }
        if (snapshot == null || event.isCancelled()) {
            return;
        }

        var playerId = snapshot.awaitingPlacementPlayerId();
        if (playerId != null) {
            synchronized (this.inFlight) {
                this.pendingPlayerPlacements.put(
                    new PlayerPlacementKey(
                        snapshot.worldKey(),
                        snapshot.position(),
                        playerId
                    ),
                    snapshot
                );
            }
            return;
        }

        submit(this.api, EVENT_TYPE, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizePlacement(BlockPlaceEvent event) {
        Objects.requireNonNull(event, "event");
        var playerId = event.getPlayer().getUniqueId();
        var snapshots = removePendingPlacements(event, playerId);
        if (snapshots.isEmpty() || event.isCancelled() || !event.canBuild()) {
            return;
        }
        for (var snapshot : snapshots) {
            submit(this.api, EVENT_TYPE, snapshot);
        }
    }

    @Override
    public void clearInFlightState() {
        synchronized (this.inFlight) {
            this.inFlight.clear();
            this.pendingPlayerPlacements.clear();
        }
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            return this.inFlight.size() + this.pendingPlayerPlacements.size();
        }
    }

    private List<Snapshot> removePendingPlacements(BlockPlaceEvent event, UUID playerId) {
        var result = new ArrayList<Snapshot>();
        synchronized (this.inFlight) {
            if (event instanceof BlockMultiPlaceEvent multiPlaceEvent) {
                for (var state : multiPlaceEvent.getReplacedBlockStates()) {
                    var snapshot = this.pendingPlayerPlacements.remove(
                        placementKey(playerId, state)
                    );
                    if (snapshot != null) {
                        result.add(snapshot);
                    }
                }
            } else {
                var snapshot = this.pendingPlayerPlacements.remove(
                    placementKey(playerId, event.getBlockPlaced())
                );
                if (snapshot != null) {
                    result.add(snapshot);
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean awaitsPlayerPlacement(
        BlockIgniteEvent.IgniteCause cause,
        @Nullable UUID playerId
    ) {
        return playerId != null
            && (
                cause == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                    || cause == BlockIgniteEvent.IgniteCause.FIREBALL
            );
    }

    private static PlayerPlacementKey placementKey(UUID playerId, Block block) {
        return new PlayerPlacementKey(
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            playerId
        );
    }

    private static PlayerPlacementKey placementKey(UUID playerId, BlockState state) {
        return new PlayerPlacementKey(
            PaperKansokusha.key(state.getWorld().getKey()),
            new BlockPosition(state.getX(), state.getY(), state.getZ()),
            playerId
        );
    }

    private static @Nullable SourceBlock source(@Nullable Block block) {
        return block == null ? null : new SourceBlock(position(block), block.getBlockData());
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    private static void registerEventType(KansokushaApi api, EventTypeDefinition definition) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(definition);
        if (outcome != RegistrationOutcome.REGISTERED && outcome != RegistrationOutcome.ALREADY_REGISTERED) {
            throw new IllegalStateException(
                "Could not register built-in event type " + definition.key() + ": " + outcome
            );
        }
    }

    private static void submit(KansokushaApi api, Key eventType, Snapshot snapshot) {
        api.submit(new EventSubmission(
            eventType,
            PayloadGeneration.FIRST,
            snapshot.occurredAt(),
            snapshot.serverKey(),
            snapshot.worldKey(),
            snapshot.position(),
            snapshot.subject(),
            snapshot.payload()
        ));
    }

    private record SourceBlock(BlockPosition position, BlockData state) {
    }

    private record PlayerPlacementKey(
        Key worldKey,
        BlockPosition position,
        UUID playerId
    ) {
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        @Nullable PlayerSubject subject,
        EventPayload payload,
        @Nullable UUID awaitingPlacementPlayerId
    ) {
    }
}
