package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Scaffolding;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.StructureGrowEvent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

@ApiStatus.Internal
@NotNullByDefault
public final class PaperNaturalBlockChangeListener implements PaperInFlightListener {

    static final Key EVENT_TYPE = Key.key("kansokusha", "natural_block_change");
    private static final EventTypeDefinition DEFINITION =
        new EventTypeDefinition(EVENT_TYPE, PayloadGeneration.FIRST);

    private final KansokushaApi api;
    private final Key serverKey;
    private final Clock clock;
    private final BiConsumer<Location, Runnable> nextTickExecutor;
    private final Map<Event, Capture> inFlight = new IdentityHashMap<>();
    private final List<DeferredChange> deferredChanges = new ArrayList<>();
    private final List<DeferredFade> deferredFades = new ArrayList<>();
    private final Map<ScaffoldingFadeKey, ArrayDeque<PendingScaffoldingFade>> pendingScaffoldingFades =
        new HashMap<>();

    private PaperNaturalBlockChangeListener(
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

    public static PaperNaturalBlockChangeListener register(KansokushaApi api, Key serverKey) {
        return register(
            api,
            serverKey,
            Clock.systemUTC(),
            PaperNaturalBlockChangeListener::scheduleNextTick
        );
    }

    static PaperNaturalBlockChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock
    ) {
        return register(api, serverKey, clock, (location, task) -> task.run());
    }

    static PaperNaturalBlockChangeListener register(
        KansokushaApi api,
        Key serverKey,
        Clock clock,
        BiConsumer<Location, Runnable> nextTickExecutor
    ) {
        Objects.requireNonNull(api, "api");
        var outcome = api.registerEventType(DEFINITION);
        if (outcome != RegistrationOutcome.REGISTERED && outcome != RegistrationOutcome.ALREADY_REGISTERED) {
            throw new IllegalStateException(
                "Could not register built-in event type " + EVENT_TYPE + ": " + outcome
            );
        }
        return new PaperNaturalBlockChangeListener(api, serverKey, clock, nextTickExecutor);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockFadeEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        var preState = block.getBlockData().clone();
        var postState = event.getNewState().getBlockData().clone();
        put(
            event,
            new FadeCapture(
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                position(block),
                preState,
                postState,
                awaitsScaffoldingEntityChange(preState)
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockFadeEvent event) {
        Objects.requireNonNull(event, "event");
        var capture = remove(event, FadeCapture.class);
        if (capture == null || event.isCancelled()) {
            return;
        }

        if (capture.awaitsScaffoldingEntityChange()) {
            var key = new ScaffoldingFadeKey(
                Thread.currentThread(),
                capture.worldKey(),
                capture.position()
            );
            synchronized (this.inFlight) {
                this.pendingScaffoldingFades
                    .computeIfAbsent(key, ignored -> new ArrayDeque<>())
                    .addLast(new PendingScaffoldingFade(capture.postState()));
            }
            return;
        }

        var finalPostState = event.getNewState().getBlockData().clone();
        if (sameBlockData(capture.postState(), finalPostState)) {
            submitFadeIfChanged(capture, capture.postState());
            return;
        }

        deferFade(event.getBlock(), capture, finalPostState);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureScaffoldingFall(EntityChangeBlockEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) {
            return;
        }
        if (fallingBlock.getBlockData().getMaterial() != Material.SCAFFOLDING) {
            return;
        }

        var block = event.getBlock();
        put(
            event,
            new ScaffoldingEntityChangeCapture(
                new ScaffoldingFadeKey(
                    Thread.currentThread(),
                    PaperKansokusha.key(block.getWorld().getKey()),
                    position(block)
                ),
                event.getBlockData().clone()
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeScaffoldingFall(EntityChangeBlockEvent event) {
        Objects.requireNonNull(event, "event");
        var capture = remove(event, ScaffoldingEntityChangeCapture.class);
        if (capture == null) {
            return;
        }

        PendingScaffoldingFade pending = null;
        synchronized (this.inFlight) {
            var queue = this.pendingScaffoldingFades.get(capture.key());
            if (queue != null) {
                for (var iterator = queue.descendingIterator(); iterator.hasNext();) {
                    var candidate = iterator.next();
                    if (sameBlockData(candidate.postState(), capture.targetState())) {
                        pending = candidate;
                        iterator.remove();
                        break;
                    }
                }
                if (queue.isEmpty()) {
                    this.pendingScaffoldingFades.remove(capture.key());
                }
            }
        }

        if (pending == null || event.isCancelled()) {
            return;
        }

        // Falling scaffolding source removal is canonically owned by EntityChangeBlockEvent (#117).
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockFormEvent event) {
        if (event instanceof BlockSpreadEvent || event instanceof EntityBlockFormEvent) {
            return;
        }
        captureTransition(event, event.getBlock(), "block_form", null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockFormEvent event) {
        if (event instanceof BlockSpreadEvent || event instanceof EntityBlockFormEvent) {
            return;
        }
        finalizeTransition(event, event, event.getBlock(), event.getNewState(), false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockGrowEvent event) {
        if (event instanceof BlockFormEvent) {
            return;
        }
        captureTransition(event, event.getBlock(), "block_grow", null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockGrowEvent event) {
        if (event instanceof BlockFormEvent) {
            return;
        }
        finalizeTransition(event, event, event.getBlock(), event.getNewState(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockSpreadEvent event) {
        var source = event.getSource();
        captureTransition(
            event,
            event.getBlock(),
            "block_spread",
            source.getType().name().toLowerCase(Locale.ROOT),
            position(source)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(BlockSpreadEvent event) {
        finalizeTransition(event, event, event.getBlock(), event.getNewState(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(LeavesDecayEvent event) {
        Objects.requireNonNull(event, "event");
        var block = event.getBlock();
        var snapshot = new Snapshot(
            this.clock.instant(),
            this.serverKey,
            PaperKansokusha.key(block.getWorld().getKey()),
            position(block),
            PaperBlockEventPayloadCodec.encodeLeavesDecay(block.getBlockData()),
            block.getType()
        );
        put(event, new SnapshotCapture(List.of(snapshot)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(LeavesDecayEvent event) {
        Objects.requireNonNull(event, "event");
        var capture = remove(event, SnapshotCapture.class);
        if (capture == null || event.isCancelled()) {
            return;
        }
        for (var snapshot : capture.snapshots()) {
            var expectedMaterial = snapshot.requiredCurrentMaterial();
            if (expectedMaterial != null && event.getBlock().getType() != expectedMaterial) {
                return;
            }
        }
        submitSnapshots(capture.snapshots());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(MoistureChangeEvent event) {
        captureTransition(event, event.getBlock(), "moisture_change", null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(MoistureChangeEvent event) {
        finalizeTransition(event, event, event.getBlock(), event.getNewState(), false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(StructureGrowEvent event) {
        Objects.requireNonNull(event, "event");

        if (event.isFromBonemeal()) {
            return;
        }

        var preStates = new HashMap<BlockKey, BlockData>();
        for (var state : event.getBlocks()) {
            preStates.putIfAbsent(
                blockKey(state),
                state.getBlock().getBlockData().clone()
            );
        }

        put(
            event,
            new StructureGrowCapture(
                this.clock.instant(),
                this.serverKey,
                event.getSpecies().name().toLowerCase(Locale.ROOT),
                Map.copyOf(preStates)
            )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeEvent(StructureGrowEvent event) {
        Objects.requireNonNull(event, "event");
        var capture = remove(event, StructureGrowCapture.class);
        if (capture == null || event.isCancelled()) {
            return;
        }

        var finalStates = event.getBlocks();
        if (finalStates.isEmpty()) {
            return;
        }

        var finalStatesByBlock = new LinkedHashMap<BlockKey, BlockState>();
        for (var state : finalStates) {
            finalStatesByBlock.put(blockKey(state), state);
        }

        var snapshots = new ArrayList<Snapshot>(finalStatesByBlock.size());
        for (var entry : finalStatesByBlock.entrySet()) {
            var key = entry.getKey();
            var state = entry.getValue();
            var preState = capture.preStates().get(key);
            if (preState == null) {
                preState = state.getBlock().getBlockData().clone();
            }
            var finalPostState = state.getBlockData().clone();
            if (sameBlockData(preState, finalPostState)) {
                continue;
            }
            snapshots.add(new Snapshot(
                capture.occurredAt(),
                capture.serverKey(),
                key.worldKey(),
                key.position(),
                PaperBlockEventPayloadCodec.encodeNaturalChange(
                    preState,
                    finalPostState,
                    "structure_grow",
                    capture.cause(),
                    null
                )
            ));
        }

        if (snapshots.isEmpty()) {
            return;
        }

        defer(
            event.getLocation(),
            finalStates,
            null,
            List.copyOf(snapshots)
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void capture(BlockFertilizeEvent event) {
        Objects.requireNonNull(event, "event");
        var fertilizedBlocks = event.getBlocks();
        var expectedStates = new HashMap<BlockKey, BlockData>();
        for (var state : fertilizedBlocks) {
            expectedStates.putIfAbsent(
                blockKey(state),
                state.getBlock().getBlockData().clone()
            );
        }

        var currentThread = Thread.currentThread();
        synchronized (this.inFlight) {
            for (int i = 0; i < this.deferredChanges.size();) {
                var deferred = this.deferredChanges.get(i);
                if (deferred.ownerThread() != currentThread) {
                    i++;
                    continue;
                }

                if (deferred.changedStatesIdentity() == fertilizedBlocks) {
                    this.deferredChanges.remove(i);
                    continue;
                }

                var transition = deferred.fertilizationTransition();
                if (transition == null) {
                    i++;
                    continue;
                }

                var expectedState = expectedStates.get(transition.block());
                if (
                    expectedState == null
                        || !sameBlockData(transition.preState(), expectedState)
                ) {
                    i++;
                    continue;
                }

                expectedStates.put(transition.block(), transition.postState());
                this.deferredChanges.remove(i);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void discardFertilizedChanges(BlockFertilizeEvent event) {
        Objects.requireNonNull(event, "event");
    }

    @Override
    public void clearInFlightState() {
        synchronized (this.inFlight) {
            this.inFlight.clear();
            this.deferredChanges.clear();
            this.deferredFades.clear();
            this.pendingScaffoldingFades.clear();
        }
    }

    int inFlightCount() {
        synchronized (this.inFlight) {
            var scaffoldingFadeCount = 0;
            for (var queue : this.pendingScaffoldingFades.values()) {
                scaffoldingFadeCount += queue.size();
            }
            return this.inFlight.size()
                + this.deferredChanges.size()
                + this.deferredFades.size()
                + scaffoldingFadeCount;
        }
    }

    private void captureTransition(
        Event event,
        Block block,
        String sourceEvent,
        @Nullable String cause,
        @Nullable BlockPosition sourcePosition
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(block, "block");
        put(
            event,
            new TransitionCapture(
                this.clock.instant(),
                this.serverKey,
                PaperKansokusha.key(block.getWorld().getKey()),
                position(block),
                block.getBlockData().clone(),
                sourceEvent,
                cause,
                sourcePosition
            )
        );
    }

    private void finalizeTransition(
        Event event,
        Cancellable cancellable,
        Block block,
        BlockState postState,
        boolean deferForFertilization
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(cancellable, "cancellable");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(postState, "postState");

        var capture = remove(event, TransitionCapture.class);
        if (capture == null || cancellable.isCancelled()) {
            return;
        }

        var finalPostState = postState.getBlockData().clone();
        if (sameBlockData(capture.preState(), finalPostState)) {
            return;
        }

        var snapshot = new Snapshot(
            capture.occurredAt(),
            capture.serverKey(),
            capture.worldKey(),
            capture.position(),
            PaperBlockEventPayloadCodec.encodeNaturalChange(
                capture.preState(),
                finalPostState,
                capture.sourceEvent(),
                capture.cause(),
                capture.sourcePosition()
            )
        );

        if (!deferForFertilization) {
            submitSnapshots(List.of(snapshot));
            return;
        }

        defer(
            location(block),
            null,
            new FertilizationTransition(
                new BlockKey(capture.worldKey(), capture.position()),
                capture.preState(),
                finalPostState
            ),
            List.of(snapshot)
        );
    }

    private void deferFade(Block block, FadeCapture capture, BlockData mutablePostState) {
        var deferred = new DeferredFade(block, capture, mutablePostState);
        synchronized (this.inFlight) {
            this.deferredFades.add(deferred);
        }
        this.nextTickExecutor.accept(location(block), () -> finalizeDeferredFade(deferred));
    }

    private void finalizeDeferredFade(DeferredFade deferred) {
        synchronized (this.inFlight) {
            var found = false;
            for (int i = 0; i < this.deferredFades.size(); i++) {
                if (this.deferredFades.get(i) == deferred) {
                    this.deferredFades.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return;
            }
        }

        var actualState = deferred.block().getBlockData();
        var capture = deferred.capture();
        if (sameBlockData(actualState, deferred.mutablePostState())) {
            submitFadeIfChanged(capture, deferred.mutablePostState());
        } else if (sameBlockData(actualState, capture.postState())) {
            submitFadeIfChanged(capture, capture.postState());
        }
    }

    private void submitFadeIfChanged(FadeCapture capture, BlockData postState) {
        if (sameBlockData(capture.preState(), postState)) {
            return;
        }
        submitSnapshots(List.of(fadeSnapshot(capture, postState)));
    }

    private static Snapshot fadeSnapshot(FadeCapture capture, BlockData postState) {
        return new Snapshot(
            capture.occurredAt(),
            capture.serverKey(),
            capture.worldKey(),
            capture.position(),
            PaperBlockEventPayloadCodec.encodeNaturalChange(
                capture.preState(),
                postState,
                "block_fade",
                null,
                null
            )
        );
    }

    private void defer(
        Location location,
        @Nullable List<BlockState> changedStatesIdentity,
        @Nullable FertilizationTransition fertilizationTransition,
        List<Snapshot> snapshots
    ) {
        var deferred = new DeferredChange(
            Thread.currentThread(),
            changedStatesIdentity,
            fertilizationTransition,
            snapshots
        );
        synchronized (this.inFlight) {
            this.deferredChanges.add(deferred);
        }
        this.nextTickExecutor.accept(location, () -> finalizeDeferredChange(deferred));
    }

    private void finalizeDeferredChange(DeferredChange deferred) {
        synchronized (this.inFlight) {
            var found = false;
            for (int i = 0; i < this.deferredChanges.size(); i++) {
                if (this.deferredChanges.get(i) == deferred) {
                    this.deferredChanges.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return;
            }
        }
        submitSnapshots(deferred.snapshots());
    }

    private void submitSnapshots(List<Snapshot> snapshots) {
        for (var snapshot : snapshots) {
            this.api.submit(new EventSubmission(
                EVENT_TYPE,
                PayloadGeneration.FIRST,
                snapshot.occurredAt(),
                snapshot.serverKey(),
                snapshot.worldKey(),
                snapshot.position(),
                null,
                snapshot.payload()
            ));
        }
    }

    private void put(Event event, Capture capture) {
        synchronized (this.inFlight) {
            this.inFlight.put(event, capture);
        }
    }

    private <T extends Capture> @Nullable T remove(Event event, Class<T> type) {
        Capture capture;
        synchronized (this.inFlight) {
            capture = this.inFlight.remove(event);
        }
        return type.isInstance(capture) ? type.cast(capture) : null;
    }

    private static BlockKey blockKey(BlockState state) {
        return new BlockKey(
            PaperKansokusha.key(state.getWorld().getKey()),
            new BlockPosition(state.getX(), state.getY(), state.getZ())
        );
    }

    private static boolean awaitsScaffoldingEntityChange(BlockData preState) {
        return preState instanceof Scaffolding scaffolding
            && scaffolding.getDistance() == scaffolding.getMaximumDistance();
    }

    private static boolean sameBlockData(BlockData first, BlockData second) {
        return first.getAsString().equals(second.getAsString());
    }

    private static Location location(Block block) {
        return new Location(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private static void scheduleNextTick(Location location, Runnable task) {
        var plugin = JavaPlugin.getProvidingPlugin(PaperNaturalBlockChangeListener.class);
        Bukkit.getRegionScheduler().run(plugin, location, ignored -> task.run());
    }

    private static BlockPosition position(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    private interface Capture {
    }

    private record TransitionCapture(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        BlockData preState,
        String sourceEvent,
        @Nullable String cause,
        @Nullable BlockPosition sourcePosition
    ) implements Capture {
    }

    private record FadeCapture(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        BlockData preState,
        BlockData postState,
        boolean awaitsScaffoldingEntityChange
    ) implements Capture {
    }

    private record SnapshotCapture(
        List<Snapshot> snapshots
    ) implements Capture {
    }

    private record StructureGrowCapture(
        Instant occurredAt,
        Key serverKey,
        String cause,
        Map<BlockKey, BlockData> preStates
    ) implements Capture {
    }

    private record BlockKey(
        Key worldKey,
        BlockPosition position
    ) {
    }

    private record ScaffoldingFadeKey(
        Thread ownerThread,
        Key worldKey,
        BlockPosition position
    ) {
    }

    private record ScaffoldingEntityChangeCapture(
        ScaffoldingFadeKey key,
        BlockData targetState
    ) implements Capture {
    }

    private record PendingScaffoldingFade(
        BlockData postState
    ) {
    }

    private record FertilizationTransition(
        BlockKey block,
        BlockData preState,
        BlockData postState
    ) {
    }

    private record DeferredFade(
        Block block,
        FadeCapture capture,
        BlockData mutablePostState
    ) {
    }

    private record DeferredChange(
        Thread ownerThread,
        @Nullable List<BlockState> changedStatesIdentity,
        @Nullable FertilizationTransition fertilizationTransition,
        List<Snapshot> snapshots
    ) {
    }

    private record Snapshot(
        Instant occurredAt,
        Key serverKey,
        Key worldKey,
        BlockPosition position,
        EventPayload payload,
        @Nullable Material requiredCurrentMaterial
    ) {

        private Snapshot(
            Instant occurredAt,
            Key serverKey,
            Key worldKey,
            BlockPosition position,
            EventPayload payload
        ) {
            this(occurredAt, serverKey, worldKey, position, payload, null);
        }
    }
}
