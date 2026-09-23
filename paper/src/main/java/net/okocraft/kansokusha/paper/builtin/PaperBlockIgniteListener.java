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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperBlockIgniteListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "block_ignite");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final BiConsumer<Location, Runnable> nextTickExecutor;
    private final Map<BlockIgniteEvent, Snapshot> inFlight = new IdentityHashMap<>();
    private final Map<PlayerPlacementKey, ArrayDeque<PendingPlacement>> pendingPlayerPlacements =
        new HashMap<>();

    private PaperBlockIgniteListener(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        BiConsumer<Location, Runnable> nextTickExecutor
    ) {
        this.api = Objects.requireNonNull(api, "api");
        this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nextTickExecutor = Objects.requireNonNull(nextTickExecutor, "nextTickExecutor");
    }

    public static PaperBlockIgniteListener register(KansokushaApi api, Key serverKey) {
        return register(
            api,
            serverKey,
            Clock.systemUTC(),
            PaperBlockIgniteListener::scheduleNextTick
        );
    }

    static PaperBlockIgniteListener register(KansokushaApi api, Key serverKey, Clock clock) {
        return register(api, serverKey, clock, (location, task) -> task.run());
    }

    static PaperBlockIgniteListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        BiConsumer<Location, Runnable> nextTickExecutor
    ) {
        registerEventType(api, DEFINITION);
        return new PaperBlockIgniteListener(api, serverKey, clock, nextTickExecutor);
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
            null,
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
        if (playerId == null) {
            submit(this.api, EVENT_TYPE, snapshot);
            return;
        }

        var key = new PlayerPlacementKey(
            snapshot.worldKey(),
            snapshot.position(),
            playerId
        );
        var pending = new PendingPlacement(snapshot);
        synchronized (this.inFlight) {
            this.pendingPlayerPlacements
                .computeIfAbsent(key, ignored -> new ArrayDeque<>())
                .addLast(pending);
        }

        this.nextTickExecutor.accept(
            location(event.getBlock()),
            () -> finalizePendingPlacement(key, pending)
        );
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
            submit(this.api, EVENT_TYPE, withPlayerSubject(snapshot, playerId));
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
            var pendingCount = 0;
            for (var queue : this.pendingPlayerPlacements.values()) {
                pendingCount += queue.size();
            }
            return this.inFlight.size() + pendingCount;
        }
    }

    private void finalizePendingPlacement(
        PlayerPlacementKey key,
        PendingPlacement pending
    ) {
        var removed = false;
        synchronized (this.inFlight) {
            var queue = this.pendingPlayerPlacements.get(key);
            if (queue != null) {
                removed = queue.remove(pending);
                if (queue.isEmpty()) {
                    this.pendingPlayerPlacements.remove(key);
                }
            }
        }
        if (removed) {
            submit(this.api, EVENT_TYPE, pending.snapshot());
        }
    }

    private List<Snapshot> removePendingPlacements(BlockPlaceEvent event, UUID playerId) {
        var result = new ArrayList<Snapshot>();
        synchronized (this.inFlight) {
            if (event instanceof BlockMultiPlaceEvent multiPlaceEvent) {
                for (var state : multiPlaceEvent.getReplacedBlockStates()) {
                    removeLatestPending(placementKey(playerId, state), result);
                }
            } else {
                removeLatestPending(placementKey(playerId, event.getBlockPlaced()), result);
            }
        }
        return List.copyOf(result);
    }

    private void removeLatestPending(
        PlayerPlacementKey key,
        List<Snapshot> result
    ) {
        var queue = this.pendingPlayerPlacements.get(key);
        if (queue == null) {
            return;
        }
        var pending = queue.pollLast();
        if (pending != null) {
            result.add(pending.snapshot());
        }
        if (queue.isEmpty()) {
            this.pendingPlayerPlacements.remove(key);
        }
    }

    private static Snapshot withPlayerSubject(Snapshot snapshot, UUID playerId) {
        return new Snapshot(
            snapshot.occurredAt(),
            snapshot.serverKey(),
            snapshot.worldKey(),
            snapshot.position(),
            new PlayerSubject(playerId),
            snapshot.payload(),
            snapshot.awaitingPlacementPlayerId()
        );
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

    private static Location location(Block block) {
        return new Location(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private static void scheduleNextTick(Location location, Runnable task) {
        var plugin = JavaPlugin.getProvidingPlugin(PaperBlockIgniteListener.class);
        Bukkit.getRegionScheduler().run(plugin, location, ignored -> task.run());
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

    private record PendingPlacement(
        Snapshot snapshot
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
